(ns cljc.repl.compiler
  (:refer-clojure :exclude [compile *ns*])
  (:require [cljc.compiler :as compiler]
            [cljc.repl.util :refer [sh maybe-deref]]
            [clojure.java.io :refer [file]]
            [clojure.string :refer [split join]]))

(def ^:private ON_MAC (.contains (.toLowerCase (System/getProperty "os.name")) "mac os x"))
(def ^:private DBUS (Boolean/valueOf (System/getenv "CLJC_REPL_DBUS")))
(def ^:private CLOJUREC_HOME (file (System/getenv "CLOJUREC_HOME")))
(def ^:private CLJC_REPL_HOME (file (System/getenv "CLJC_REPL_HOME")))

(def ^:private CC ["gcc" "-std=gnu99"])

(def ^:private CFLAGS
  ["-Wno-unused-variable" "-Wno-unused-value" "-Wno-unused-function" "-g" "-O0"
   (split (sh "pcre-config" "--cflags") #"\s+")
   (split (sh "pkg-config" "--cflags" "bdw-gc" "glib-2.0" (when DBUS "dbus-1")) #"\s+")
   (str "-I" (file CLOJUREC_HOME "src/c"))
   (str "-I" (file CLOJUREC_HOME "run/thirdparty/klib"))])

(def ^:private LDFLAGS
  ["-lm" "-lpthread" "-ldl"
   (split (sh "pcre-config" "--libs") #"\s+")
   (split (sh "pkg-config" "--libs" "bdw-gc" "glib-2.0" (when DBUS "dbus-1")) #"\s+")])

(def ^:private libs (atom #{}))
(def ^:private search-dirs (atom #{}))

(defn- make-executable [name code & {:keys [lib prefix cache]}]
  (let [c-file (file (str name ".c"))
        o-file (file (str name ".o"))
        prefix (if prefix (str prefix "/") "./")
        out-file (file (if lib
                         (str prefix "lib" name (if ON_MAC ".dylib" ".so"))
                         (str prefix name)))
        cached-file (file (file "cache") (.getName out-file))]
    (if (and cache (.exists cached-file))
      (sh "cp" cached-file out-file)
      (let [code (maybe-deref code)]
        (spit c-file code)
        (sh CC CFLAGS (when lib "-fPIC") "-c" c-file "-o" o-file)
        (sh CC
            (when (not ON_MAC)
              "-Wl,--no-as-needed")
            LDFLAGS
            (when lib
              (if ON_MAC
                ["-dynamiclib" "-install_name" (.getCanonicalPath out-file)]
                ["-shared" (str "-Wl,-soname,lib" name ".so")]))
            (map #(str "-L" %) @search-dirs)
            (map #(str "-l" %) @libs)
            o-file "-o" out-file)
        (.delete c-file)
        (.delete o-file)
        (when cache
          (sh "cp" out-file cached-file))))
    (when lib
      (swap! libs conj name)
      (swap! search-dirs conj prefix))
    out-file))

(defn- make-library [name code & {:keys [prefix cache]}]
  (make-executable name code :lib true :prefix prefix :cache cache))

(def ^:private exports (atom []))
(def ^:dynamic *ns*)

(defn- compile [init-fn & forms]
  (compiler/reset-namespaces!)
  (binding [compiler/*read-exports-fn* (fn [_] @exports)]
    (compiler/analyze-deps [*ns*]))
  (reset! compiler/exports @exports)
  (binding [compiler/*read-exports-fn* (fn [_] nil)
            compiler/*cljs-ns* *ns*
            compiler/*cljs-file* "NO-SOURCE"]
    (let [code (with-out-str
                 (doseq [form forms]
                   (compiler/emit
                    (compiler/analyze
                     {:ns (@compiler/namespaces compiler/*cljs-ns*) :context :statement :locals {}}
                     form))))
          code (join (flatten
                      [(slurp (file CLOJUREC_HOME "src/c/preamble.c"))
                       @compiler/declarations
                       "void " init-fn " (void) {\n"
                       "environment_t *env = NULL;\n"
                       code
                       "return;\n"
                       "}\n"]))]
      (set! *ns* compiler/*cljs-ns*)
      (reset! exports @compiler/exports)
      code)))

(defn compile-runtime []
  (when DBUS
    (make-library "_proxy"
                  (delay (slurp (file CLJC_REPL_HOME "src/c/cljc/repl/runtime/dbus/proxy.c")))
                  :cache true)
    (reset! libs #{})
    (reset! search-dirs #{}))
  (make-library "_runtime"
                (delay (slurp (file CLOJUREC_HOME "src/c/runtime.c")))
                :cache true)
  (let [cached-exports (file "cache/exports.clj")]
    (if (.exists cached-exports)
      (reset! exports (read-string (slurp cached-exports)))
      (reset! exports [[:namespaces ['cljc.core :defs 'Cons] {:name 'cljc_DOT_core_SLASH_Cons}]
                       [:namespaces ['cljc.core :defs 'count] {:name 'cljc_DOT_core_SLASH_count}]
                       [:namespaces ['cljc.core :defs 'first] {:name 'cljc_DOT_core_SLASH_first}]
                       [:namespaces ['cljc.core :defs 'next] {:name 'cljc_DOT_core_SLASH_next}]
                       [:namespaces ['cljc.core :defs 'apply] {:name 'cljc_DOT_core_SLASH_apply}]
                       [:namespaces ['cljc.core :defs 'print] {:name 'cljc_DOT_core_SLASH_print}]
                       [:namespaces [*ns*] {:name *ns* :excludes #{} :uses nil :requires nil :uses-macros nil :requires-macros {}}]]))
    (make-library "_core" (delay
                           (binding [*ns* 'cljc.core]
                             (apply compile "init__core"
                                    (concat (compiler/forms-seq (file CLOJUREC_HOME "src/cljc/cljc/core.cljc"))
                                            ['(ns cljc.core)
                                             '(def *1 nil)
                                             '(def *2 nil)
                                             '(def *3 nil)]))))
                  :cache true)
    (spit cached-exports (pr-str @exports)))
  (if DBUS
    (make-executable "_repl"
                     (delay (str (slurp (file CLOJUREC_HOME "src/c/preamble.c"))
                                 (slurp (file CLJC_REPL_HOME "src/c/cljc/repl/runtime.c"))
                                 (slurp (file CLJC_REPL_HOME "src/c/cljc/repl/runtime/dbus.c"))))
                     :cache true)
    (make-library "_repl"
                  (delay (str (slurp (file CLOJUREC_HOME "src/c/preamble.c"))
                              (slurp (file CLJC_REPL_HOME "src/c/cljc/repl/runtime.c"))))
                  :cache true)))

(let [counter (atom 0)]
  (defn- generate-form-name []
    (str (compiler/munge (symbol (format "form_%04d" (swap! counter inc)))))))

(defn compile-form [form & {:keys [prefix]}]
  (let [lib-name (generate-form-name)
        init-fn (str "init_" lib-name)
        form (if (and (seq? form) (= (first form) 'ns))
               `(do ~form nil)
               form)
        form `(let [ret# ~form]
                (set! cljc.core/*3 cljc.core/*2)
                (set! cljc.core/*2 cljc.core/*1)
                (set! cljc.core/*1 ret#)
                ret#)]
    [(make-library lib-name
                   (compile init-fn form)
                   :prefix prefix)
     init-fn]))

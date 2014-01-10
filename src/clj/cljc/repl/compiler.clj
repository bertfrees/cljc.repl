(ns cljc.repl.compiler
  (:refer-clojure :exclude [compile])
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
  ["-lm" "-lpthread"
   (split (sh "pcre-config" "--libs") #"\s+")
   (split (sh "pkg-config" "--libs" "bdw-gc" "glib-2.0" (when DBUS "dbus-1")) #"\s+")])

(def ^:private libs (atom #{}))
(def ^:private search-dirs (atom #{}))

(defn- make-executable [code name & {:keys [lib prefix cache]}]
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
        (sh CC LDFLAGS
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

(defn- make-library [code name & {:keys [prefix cache]}]
  (make-executable code name :lib true :prefix prefix :cache cache))

(let [exports-map (atom {})]
  (defn- write-exports [ns exports]
    (swap! exports-map assoc ns exports))
  (defn- read-exports [ns]
    (if-let [exports (get @exports-map ns)]
      (prn-str exports)
      (throw (Error. (str "Namespace " ns " not loaded!"))))))
  
(defn- compile [files other lib-name & {:keys [ns prefix cache]
                                        :or {ns compiler/*cljs-ns*}}]
  (let [init-fn (str "init_" lib-name)
        cached-exports (file (file "cache") (str ns "-exports.clj"))]
    (if (and cache (.exists cached-exports))
      (do (write-exports ns (read-string (slurp cached-exports)))
          [(make-library nil lib-name :prefix prefix :cache cache) init-fn])
      (do
        (binding [compiler/*read-exports-fn* read-exports]
          (compiler/reset-namespaces!)
          (when (not (= ns 'cljc.core))
            (compiler/analyze-deps ['cljc.core]))
          (try
            (compiler/analyze-deps [ns])
            (reset! compiler/exports (read-string (read-exports ns)))
            (catch Throwable _))
          (let [code (with-out-str
                       (doseq [ast (compiler/analyze-files files other)]
                         (compiler/emit ast)))
                code (join (flatten
                            [(slurp (file CLOJUREC_HOME "src/c/preamble.c"))
                             @compiler/declarations
                             "void " init-fn " (void) {\n"
                             "environment_t *env = NULL;\n"
                             code
                             "return;\n"
                             "}\n"]))]
            (write-exports ns @compiler/exports)
            (when cache
              (spit cached-exports (read-exports ns)))
            [(make-library code lib-name :prefix prefix :cache cache) init-fn]))))))

(defn compile-runtime []
  (when DBUS
    (make-library (delay (slurp (file CLJC_REPL_HOME "src/c/cljc/repl/runtime/dbus/proxy.c")))
                  "_proxy"
                  :cache true)
    (reset! libs #{})
    (reset! search-dirs #{}))
  (make-library (delay (slurp (file CLOJUREC_HOME "src/c/runtime.c")))
            "_runtime"
            :cache true)
  (compile [(file CLOJUREC_HOME "src/cljc/cljc/core.cljc")]
           ['(ns cljc.core)
            '(def *ns* 'cljc.user)
            '(def *1 nil)
            '(def *2 nil)
            '(def *3 nil)]
           "_core"
           :ns 'cljc.core
           :cache true)
  (if DBUS
    (make-executable (delay (str (slurp (file CLOJUREC_HOME "src/c/preamble.c"))
                                 (slurp (file CLJC_REPL_HOME "src/c/cljc/repl/runtime.c"))
                                 (slurp (file CLJC_REPL_HOME "src/c/cljc/repl/runtime/dbus.c"))))
                     "_repl"
                     :cache true)
    (make-library (delay (str (slurp (file CLOJUREC_HOME "src/c/preamble.c"))
                              (slurp (file CLJC_REPL_HOME "src/c/cljc/repl/runtime.c"))))
                  "_repl"
                  :cache true)))

(let [counter (atom 0)]
  (defn generate-form-name []
    (str (compiler/munge (symbol (format "form_%04d" (swap! counter inc)))))))

(defn compile-form [form & {:keys [prefix]}]
  (compile [] [`(let [ret# ~form]
                  (set! cljc.core/*3 cljc.core/*2)
                  (set! cljc.core/*2 cljc.core/*1)
                  (set! cljc.core/*1 ret#)
                  ret#)]
           (generate-form-name)
           :prefix prefix))

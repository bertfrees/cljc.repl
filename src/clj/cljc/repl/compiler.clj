(ns cljc.repl.compiler
  (:refer-clojure :exclude [compile])
  (:require [cljc.compiler :as compiler]
            [cljc.repl.util :refer [sh maybe-deref]]
            [clojure.java.io :refer [file]]
            [clojure.string :refer [split join]]))

(def ^:private ON_MAC (.contains (.toLowerCase (System/getProperty "os.name")) "mac os x"))
(def ^:private CLOJUREC_HOME (file (System/getenv "CLOJUREC_HOME")))
(def ^:private CLJC_REPL_HOME (file (System/getenv "CLJC_REPL_HOME")))

(def ^:private CC ["gcc" "-std=gnu99"])

(def ^:private CFLAGS
  ["-Wno-unused-variable" "-Wno-unused-value" "-Wno-unused-function" "-g" "-O0"
   (split (sh "pcre-config" "--cflags") #"\s+")
   (split (sh "pkg-config" "--cflags" "bdw-gc" "glib-2.0") #"\s+")
   (str "-I" (file CLOJUREC_HOME "src/c"))
   (str "-I" (file CLOJUREC_HOME "run/thirdparty/klib"))])

(def ^:private LDFLAGS
  ["-lm" "-lpthread"
   (split (sh "pcre-config" "--libs") #"\s+")
   (split (sh "pkg-config" "--libs" "bdw-gc" "glib-2.0") #"\s+")])

(def ^:private libs (atom #{}))
(def ^:private search-dirs (atom #{}))

(defn- make-dynamic-lib [code lib-name & {:keys [prefix cache]}]
  (let [c-file (file (str lib-name ".c"))
        o-file (file (str lib-name ".o"))
        prefix (if prefix (str prefix "/") "./")
        lib-file (file (str prefix "lib" lib-name (if ON_MAC ".dylib" ".so")))
        cached-lib-file (file (file "cache") (.getName lib-file))]
    (if (and cache (.exists cached-lib-file))
      (sh "cp" cached-lib-file lib-file)
      (let [code (maybe-deref code)]
        (spit c-file code)
        (sh CC CFLAGS "-fPIC" "-c" c-file "-o" o-file)
        (sh CC LDFLAGS
              (if ON_MAC
                ["-dynamiclib" "-install_name" (.getCanonicalPath lib-file)]
                ["-shared" (str "-Wl,-soname,lib" lib-name ".so")])
              (map #(str "-L" %) @search-dirs)
              (map #(str "-l" %) @libs)
              o-file "-o" lib-file)
        (.delete c-file)
        (.delete o-file)
        (when cache
          (sh "cp" lib-file cached-lib-file))))
    (swap! libs conj lib-name)
    (swap! search-dirs conj prefix)
    lib-file))

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
          [(make-dynamic-lib nil lib-name :prefix prefix :cache cache) init-fn])
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
            [(make-dynamic-lib code lib-name :prefix prefix :cache cache) init-fn]))))))

  (make-dynamic-lib (delay (slurp (file CLOJUREC_HOME "src/c/runtime.c")))
                    "_runtime" :cache true)
(defn compile-runtime []
  (compile [(file CLOJUREC_HOME "src/cljc/cljc/core.cljc")]
           ['(ns cljc.core)
            '(def *ns* 'cljc.user)
            '(def *1 nil)
            '(def *2 nil)
            '(def *3 nil)]
           "_core" :ns 'cljc.core :cache true)
  (make-dynamic-lib (delay (str (slurp (file CLOJUREC_HOME "src/c/preamble.c"))
                                (slurp (file CLJC_REPL_HOME "src/c/cljc/repl/runtime.c"))))
                    "_repl" :cache true))

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

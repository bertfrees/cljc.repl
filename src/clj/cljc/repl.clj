(ns cljc.repl
  (:refer-clojure :exclude [load-file loaded-libs])
  (:require [cljc.compiler :as compiler])
  (:use [clojure.java.io :only [file]]
        [clojure.java.shell :only [sh]]
        [clojure.string :only [split join]]
        [clj-native.direct :only [defclib loadlib]]))

(def ^:private ON_MAC (.contains (.toLowerCase (System/getProperty "os.name")) "mac"))
(def ^:private CLOJUREC_HOME (file (System/getenv "CLOJUREC_HOME")))
(def ^:private CLJC_REPL_HOME (file (System/getenv "CLJC_REPL_HOME")))

(defclib runtime
  (:libname "runtime")
  (:functions
   (cljc-init cljc_init [] void)
   (load-and-evaluate load_and_evaluate [constchar* constchar*] void)))

(def ^:private loaded-namespaces (atom #{}))
(def ^:private loaded-libs (atom #{}))

(def ^:private CC ["gcc" "-std=gnu99"])

(def ^:private CFLAGS
  ["-Wno-unused-variable" "-Wno-unused-value" "-Wno-unused-function" "-g" "-O0"
   (split (:out (sh "pcre-config" "--cflags")) #"\s+")
   (split (:out (sh "pkg-config" "--cflags" "bdw-gc" "glib-2.0")) #"\s+")
   (str "-I" (file CLOJUREC_HOME "src/c"))
   (str "-I" (file CLOJUREC_HOME "run/thirdparty/klib"))])

(def ^:private LDFLAGS
  ["-lm" "-lpthread"
   (split (:out (sh "pcre-config" "--libs")) #"\s+")
   (split (:out (sh "pkg-config" "--libs" "bdw-gc" "glib-2.0")) #"\s+")
   "-L."])

(defn- exec [& cmd]
  (let [cmd (map str (flatten cmd))
        result (apply sh cmd)]
    (if (not= 0 (:exit result))
      (throw (Error. (str "Command failed:\n"
                          (join " " cmd) "\n"
                          (:err result)))))))

(defn- make-dynamic-lib [code lib-name]
  (let [c-file (file (str lib-name ".c"))
        o-file (file (str lib-name ".o"))
        lib-file (file (str "lib" lib-name (if ON_MAC ".dylib" ".so")))]
    (spit c-file code)
    (exec CC CFLAGS "-fPIC" "-c" c-file "-o" o-file)
    (exec CC LDFLAGS
          (if ON_MAC
            "-dynamiclib"
            ["-shared" (str "-Wl,-soname,lib" lib-name ".so")])
          (map #(str "-l" %) @loaded-libs)
          o-file "-o" lib-file)
    (.delete c-file)
    (.delete o-file)
    lib-file))

(let [exports-map (atom {})]
  (defn- write-exports [ns exports]
    (swap! exports-map assoc ns exports))
  (defn- read-exports [ns]
    (if-let [exports (get @exports-map ns)]
      (prn-str exports)
      (throw (Error. (str "Namespace " ns " not loaded!"))))))

(defn- load-namespace [ns])

(defn- load-file [source ns]
  (let [source (file source)]
    (binding [compiler/*read-exports-fn* read-exports]
      (compiler/reset-namespaces!)
      (when (not= ns 'cljc.core)
        (compiler/analyze-deps ['cljc.core]))
      (when (contains? @loaded-namespaces ns)
        (throw (Error. (str "FIXME: namespace " ns " already loaded"))))
      (let [init-fn (str (compiler/munge (symbol (str "init-" ns))))
            code (with-out-str
                   (doseq [ast (compiler/analyze-files [source])]
                     (compiler/emit ast)))
            code (join (flatten
                        [(slurp (file CLOJUREC_HOME "src/c/preamble.c"))
                         @compiler/declarations
                         "void " init-fn " (void) {\n"
                         "environment_t *env = NULL;\n"
                         code
                         "return;\n"
                         "}\n"]))]
        (load-and-evaluate (str (make-dynamic-lib code (str ns))) init-fn)
        (write-exports ns @compiler/exports)
        (swap! loaded-libs conj (str ns))
        (swap! loaded-namespaces conj ns)))))

(let [counter (atom 0)]
  (defn- generate-form-name []
    (format "form_%04d" (swap! counter inc))))

(defn- evaluate-form [form]
  (let [form-name (generate-form-name)
        form `(let [ret# ~form]
                (set! cljc.core/*3 cljc.core/*2)
                (set! cljc.core/*2 cljc.core/*1)
                (set! cljc.core/*1 ret#))
        ns compiler/*cljs-ns*]
    (binding [compiler/*read-exports-fn* read-exports]
      (compiler/reset-namespaces!)
      (when (not= ns 'cljc.core)
        (compiler/analyze-deps ['cljc.core]))
      (when (contains? @loaded-namespaces ns)
        (compiler/analyze-deps [ns])
        (reset! compiler/exports (read-string (read-exports ns))))
      (let [init-fn (str (compiler/munge (symbol (str "eval-" form-name))))
            code (with-out-str
                 (doseq [ast (compiler/analyze-files [] [form])]
                   (compiler/emit ast)))
            code (join (flatten
                   [(slurp (file CLOJUREC_HOME "src/c/preamble.c"))
                    @compiler/declarations
                    "extern value_t *VAR_NAME (cljc_DOT_core_SLASH__STAR_1);\n"
                    "extern value_t *VAR_NAME (cljc_DOT_core_SLASH__STAR_2);\n"
                    "extern value_t *VAR_NAME (cljc_DOT_core_SLASH__STAR_3);\n"
                    "void " init-fn " (void) {\n"
                    "environment_t *env = NULL;\n"
                    code
                    "return;\n"
                    "}\n"]))]
        (load-and-evaluate (str (make-dynamic-lib code form-name)) init-fn)
        (write-exports ns @compiler/exports)
        (swap! loaded-libs conj form-name)
        (swap! loaded-namespaces conj ns)))))

(defn- eval-and-print [form]
  (evaluate-form `(let [ret# ~form]
                    (println ret#)
                    ret#)))

(defn- read-next-form []
  (try {:status :success
        :form (binding [*ns* (create-ns compiler/*cljs-ns*)]
                (read))}
       (catch Exception e
         (println (.getMessage e))
         {:status :error})))

(defn repl []
  
  (dorun (map #(.delete (file %))
              (filter #(re-matches #"^libform_[0-9]{4}\.(?:dylib|so)$" %)
                      (.list (file ".")))))
  
  ;; compile runtime
  (when (not (.exists (file "libruntime.dylib")))
    (make-dynamic-lib (str (slurp (file CLOJUREC_HOME "src/c/runtime.c"))
                           "\n"
                           "value_t *VAR_NAME (cljc_DOT_core_SLASH__STAR_1);\n"
                           "value_t *VAR_NAME (cljc_DOT_core_SLASH__STAR_2);\n"
                           "value_t *VAR_NAME (cljc_DOT_core_SLASH__STAR_3);\n"
                           "\n"
                           (slurp (file CLJC_REPL_HOME "src/c/cljc/repl.c")))
                      "runtime"))
  (loadlib runtime)
  (swap! loaded-libs conj "runtime")
  (cljc-init)
  
  ;; compile cljc.core
  (let [lib-file (file "libcljc.core.dylib")
        exports-file (file "cljc.core-exports.clj")
        ns 'cljc.core]
    (if (and (.exists lib-file)
             (.exists exports-file))
      (do (load-and-evaluate (str lib-file) "init_cljc_DOT_core")
          (write-exports ns (read-string (slurp exports-file)))
          (swap! loaded-libs conj (str ns))
          (swap! loaded-namespaces conj ns))
      (do (load-file (file CLOJUREC_HOME "src/cljc/cljc/core.cljc") ns)
          (spit exports-file (read-exports ns)))))
  
  (println "ClojureC REPL")
  
  ;; main loop
  (loop []
    (print (str compiler/*cljs-ns* "=> "))
    (flush)
    (let [{:keys [status form]} (read-next-form)]
      (cond
       (= status :error)
         (recur)
       :else
         (do (eval-and-print form)
             (recur))))))

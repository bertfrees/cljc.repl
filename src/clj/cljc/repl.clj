(ns cljc.repl
  (:refer-clojure :exclude [read eval print loop])
  (:require [cljc.repl.compiler :as compiler]
            [cljc.repl.runtime :as runtime]))

(def options
  {:colored true})

(defn- maybe-colorize [fmt & args]
  (apply format (if (options :colored) fmt "%s") args))

(defn- read []
  (binding [*ns* (create-ns cljc.compiler/*cljs-ns*)]
    (clojure.core/print
     (maybe-colorize "\u001B[0;33m%s\u001B[0m" (format "%s=> " *ns*)))
    (flush)
    (clojure.core/read)))

(defn- eval [form]
  (let [[lib-file init-fn] (compiler/compile-form form :prefix "eval")
        {:keys [status buffer]} (runtime/eval lib-file init-fn)]
    (if (= status 0)
      buffer
      (throw (Error. buffer)))))

(defn- print [result]
  (println
   (maybe-colorize "\u001B[1m%s\u001B[0m" result)))

(defn- print-error [e]
  (println
   (maybe-colorize "\u001B[0;31m%s\u001B[0m" (.getMessage e))))

(defn- welcome []
  (println
   (maybe-colorize "\u001B[0;35m%s\u001B[0m"
                   (str ",---------------,\n"
                        "| ClojureC REPL |\n"
                        "`---------------'"))))

(defn- goodbye []
  (println)
  (println
   (maybe-colorize "\u001B[0;35m%s\u001B[0m"
                   (str "--------\n"
                        "Goodbye!"))))

(defn- loop []
  (clojure.core/loop []
    (try
      (print (eval (read)))
      (catch Throwable e
        (print-error e)))
    (recur)))

(defn repl []
  (compiler/compile-runtime)
  (runtime/load)
  (welcome)
  (.addShutdownHook (Runtime/getRuntime) (Thread. goodbye))
  (loop))

(ns cljc.repl
  (:refer-clojure :exclude [read eval print loop])
  (:require [cljc.repl.compiler :as compiler]
            [cljc.repl.core :as core]))


(defn- read []
  (binding [*ns* (create-ns cljc.compiler/*cljs-ns*)]
    (clojure.core/print (format "%s=> " *ns*))
    (flush)
    (clojure.core/read)))

(defn- eval [form]
  (let [[lib-file init-fn] (compiler/compile-form form :prefix "eval")
        {:keys [status buffer]} (core/eval lib-file init-fn)]
    (if (= status 0)
      buffer
      (throw (Error. buffer)))))

(defn- print [result]
  (println result))

(defn- print-error [e]
  (println (.getMessage e)))

(defn- loop []
  (clojure.core/loop []
    (try
      (print (eval (read)))
      (catch Throwable e
        (print-error e)))
    (recur)))

(defn repl []
  (compiler/compile-core)
  (core/load)
  (loop))

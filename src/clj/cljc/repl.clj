(ns cljc.repl
  (:refer-clojure :exclude [read eval print loop])
  (:require [cljc.repl.compiler :as compiler]
            [cljc.repl.util :refer [maybe-colorize map-stderr]]))

(def ^:private DBUS (Boolean/valueOf (System/getenv "CLJC_REPL_DBUS")))

(if DBUS
  (require '[cljc.repl.runtime.dbus :as runtime])
  (require '[cljc.repl.runtime :as runtime]))

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
  (Thread/sleep 10)
  (println
   (maybe-colorize "\u001B[1m%s\u001B[0m" result)))

(defn- print-error [e]
  (binding [*out* *err*]
    (println (.getMessage e))))

(defmacro maybe-colorize-stderr [& body]
  `(map-stderr
    #(maybe-colorize "\u001B[0;31m%s\u001B[0m" %)
    ~@body))

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
                   (str "\n"
                        "Goodbye!"))))

(defn- loop []
  (clojure.core/loop []
    (try
      (print (eval (read)))
      (catch Throwable e
        (print-error e)))
    (recur)))

(defn repl []
  (maybe-colorize-stderr   
   (compiler/compile-runtime)
   (runtime/load)
   (welcome)
   (.addShutdownHook (Runtime/getRuntime) (Thread. goodbye))
   (loop)))

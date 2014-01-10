(ns cljc.repl.util
  (:require [clojure.string :refer [join]]
            [clojure.java.shell :as shell]))

(def ^:private COLORS (Boolean/valueOf (System/getenv "CLJC_REPL_COLORS")))

(defn maybe-colorize [fmt & args]
  (apply format (if COLORS fmt "%s") args))

(defn read-fields [struct fields]
  (loop [fields fields
         result {}]
    (if-let [field (first fields)]
      (recur (rest fields)
             (assoc result field (.readField struct (name field))))
      result)))

(defn sh [& cmd]
  (let [cmd (remove nil? (map str (flatten cmd)))
        result (apply shell/sh cmd)]
    (if (= 0 (:exit result))
      (:out result)
      (throw (Error. (str (join " " cmd) "\n"
                          (:err result)))))))

(defn maybe-deref [x]
  (if (instance? clojure.lang.IDeref x) (deref x) x))

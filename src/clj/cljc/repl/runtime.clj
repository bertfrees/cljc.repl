(ns cljc.repl.runtime
  (:refer-clojure :exclude [eval load])
  (:require [clj-native.direct :refer [defclib loadlib]]))

(defclib runtime
  (:libname "_repl")
  (:structs
   (repl_result :status int :buffer constchar*))
  (:functions
   (repl_init repl_init [] void)
   (repl_eval repl_eval [constchar* constchar*] repl_result)))

(defn- read-fields [struct fields]
  (loop [fields fields
         result {}]
    (if-let [field (first fields)]
      (recur (rest fields)
             (assoc result field (.readField struct (name field))))
      result)))

(defn load []
  (loadlib runtime)
  (repl_init))

(defn eval [lib-file init-fn]
  (read-fields (repl_eval (str lib-file) init-fn) [:status :buffer]))

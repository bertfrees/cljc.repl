(ns cljc.repl.core
  (:refer-clojure :exclude [eval load])
  (:use [clj-native.direct :only [defclib loadlib]]))

(defclib core
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
  (loadlib core)
  (repl_init))

(defn eval [lib-file init-fn]
  (read-fields (repl_eval (str lib-file) init-fn) [:status :buffer]))

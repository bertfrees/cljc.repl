(ns cljc.repl.runtime
  (:refer-clojure :exclude [eval load])
  (:require [cljc.repl.util :refer [read-fields]]
            [clj-native.direct :refer [defclib loadlib]]))

(defclib runtime
  (:libname "_repl")
  (:structs
   (repl_result :status int :buffer constchar*))
  (:functions
   (repl_init repl_init [] void)
   (repl_eval repl_eval [constchar* constchar*] repl_result)))

(defn load []
  (loadlib runtime)
  (repl_init))

(defn eval [lib-file init-fn]
  (read-fields (repl_eval (str lib-file) init-fn) [:status :buffer]))

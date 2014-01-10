(ns cljc.repl.runtime.dbus
  (:refer-clojure :exclude [eval load proxy])
  (:require [cljc.repl.util :refer [read-fields redirect-process]]
            [clj-native.direct :refer [defclib loadlib]]))

(defclib proxy
  (:libname "_proxy")
  (:structs
   (repl_result :status int :buffer constchar*))
  (:functions
   (repl_eval repl_eval [constchar* constchar*] repl_result)))

(defn load []
  (redirect-process (.exec (Runtime/getRuntime) "./_repl"))
  (loadlib proxy))

(defn eval [lib-file init-fn]
  (read-fields (repl_eval (str lib-file) init-fn) [:status :buffer]))

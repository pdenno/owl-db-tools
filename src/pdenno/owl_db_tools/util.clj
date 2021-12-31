(ns pdenno.owl-db-tools.util
  (:require
   [taoensso.timbre :as log]))

(defn no-host&time-output-fn
  "I don't want :hostname_ and :timestamp_ in the log output."
  ([data]       (taoensso.timbre/default-output-fn nil  (dissoc data :hostname_ :timestamp_)))
  ([opts data]  (taoensso.timbre/default-output-fn opts (dissoc data :hostname_ :timestamp_))))

(defn config-log
  "Configure Timbre: set reporting levels and drop reporting host and time."
  [level]
  (log/set-config!
   (-> log/*config*
       (assoc :output-fn #'no-host&time-output-fn)
       (assoc :min-level [[#{"datahike.*"} :error]
                          [#{"*"} level]]))))

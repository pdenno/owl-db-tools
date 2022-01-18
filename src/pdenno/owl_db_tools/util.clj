(ns pdenno.owl-db-tools.util
  (:require
   [datahike.api               :as d]
   [datahike.pull-api          :as dp]
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

(defn pull-rand
  "dp/pull randomly at most CNT objects of where :db/ident is DB-IDENT."
  [db-attr cnt conn]
  (let [ents (-> (d/q `[:find [(~'rand ~cnt ?e)] :where [?e ~db-attr _]] conn))]
    (dp/pull-many conn '[*] (flatten ents))))

(defn resolve-obj
  "Resolve :db/id in the argument map."
  [m conn & {:keys [keep-db-ids?]}]
  (letfn [(subobj [x]
            (cond (and (map? x) (contains? x :resource/id)) (:resource/id x),          ; It is a whole resource, return ref.
                  (and (map? x) (contains? x :db/id) (== (count x) 1))                 ; It is an object ref...
                  (or (and (map? x)
                           (contains? x :db/id)
                           (d/q `[:find ?id . :where [~(:db/id x) :resource/id ?id]] conn)) ; ...return keyword if it is a resource...
                      (subobj (dp/pull conn '[*] (:db/id x)))),                             ; ...otherwise it is some other structure.
                  (map? x) (reduce-kv
                            (fn [m k v] (if (and (= k :db/id) (not keep-db-ids?)) m (assoc m k (subobj v))))
                            {} x),
                  (vector? x) (mapv subobj x),
                  :else x))]
    (-> (reduce-kv (fn [m k v] (assoc m k (subobj v))) {} m)
        (dissoc :db/id))))

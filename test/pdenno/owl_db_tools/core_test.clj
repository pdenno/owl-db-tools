(ns pdenno.owl-db-tools.core-test
  (:require
   [clojure.set           :as sets]
   [clojure.test :refer  [deftest is testing]]
   [clojure.pprint :refer [pprint]]
   [datahike.api          :as d]
   [edu.ucdenver.ccp.kr.jena.kb]
   [pdenno.owl-db-tools.core :as owl]
   [pdenno.owl-db-tools.util :as util]
   [pdenno.owl-db-tools.resolvers :as res]
   [taoensso.timbre          :as log]))

(util/config-log :info)

;;; ---------------- Challenging small test case ----------------------------------
;;; The "challenge" here is just dealing with some instance data, such as:
;;;   <owl:oneOf rdf:parseType="Collection">
;;;     <edns:theory rdf:about="http://www.ontologydesignpatterns.org/ont/dlp/SemioticCommunicationTheory.owl#s-communication-theory"/>
;;;   </owl:oneOf>
(def info-cfg {:store {:backend :mem :id "test"} :keep-history? false :schema-flexibility :write})
(def info-sources {"info"  {:uri "http://www.ontologydesignpatterns.org/ont/dlp/InformationObjects.owl"}})
(def info-user-attrs [#:db{:ident :testing/found? :cardinality :db.cardinality/one :valueType :db.type/boolean}])
(def info-atm (atom nil))

(defn make-info-db [cfg]
  (when (d/database-exists? cfg) (d/delete-database cfg))
  (reset! info-atm (owl/create-db! cfg
                                   info-sources
                                   :user-attrs info-user-attrs
                                   :rebuild? true)))

(deftest user-and-learned-attrs
  (testing "Testing that some attributes are learned and one more provided by the users is kept."
    (is (every? identity ((juxt make-info-db d/database-exists?) info-cfg)))
    (is (= #{:testing/found? :edns/defined-by :edns/specializes :edns/specialized-by :edns/defines}
           (->> (owl/schema-attributes @info-atm) (map :db/ident) set)))))

;;; ---------------- Modal test case ----------------------------------
(def modal-cfg {:store {:backend :mem :id "test"} :keep-history? false :schema-flexibility :write})

(def modal-sources
  {"edns"  {:uri "http://www.ontologydesignpatterns.org/ont/dlp/ExtendedDnS.owl"},
   "modal" {:uri "http://www.ontologydesignpatterns.org/ont/dlp/ModalDescriptions.owl"}})

(def modal-atm (atom nil))

(defn make-modal-db [cfg]
  (when (d/database-exists? modal-cfg) (d/delete-database cfg))
  (reset! modal-atm (owl/create-db! cfg modal-sources :rebuild? true)))

(deftest modal-db-test ; ToDo: define more tests here to justify its existance.
  (testing "Testing that some attributes are learned and one more provided by the users is kept."
    (is (every? identity ((juxt make-modal-db d/database-exists?) modal-cfg)))))

;;; ---------------- Medium test case ---------------------------------- ; ToDo This isn't so medium!
(def medium-cfg {:store {:backend :mem :id "test"} :keep-history? false :schema-flexibility :write})
(def medium-atm (atom nil))

(defn make-medium-db [cfg]
   (when (d/database-exists? cfg) (d/delete-database cfg))
  (reset! medium-atm (owl/create-db! cfg
                                     {"dol" {:uri "http://www.ontologydesignpatterns.org/ont/dlp/DOLCE-Lite.owl"}}
                                     :rebuild? true)))

(defn write-resources-map
  "Writes a file data/example-dolce.edn used in testing testing.
   It is NOT executed in testing."
  []
  (make-medium-db medium-cfg)
  (let [sorted-resources (-> (d/q '[:find [?v ...] :where [_ :resource/id ?v]] @medium-atm) sort)]
    (->> (with-out-str
           (println "{")
           (doseq [obj sorted-resources]
             (println "\n\n" obj)
             (pprint (res/pull-resource obj @medium-atm)))
           (println "}"))
         (spit "data/example-dolce.edn"))))

(defn db-match?
  "Check that the DB created is like the gold standard.
   Do this outsided the test/is and by individual resource so that
   testing/is doesn't return a horrendously large report."
  [conn]
  (let [gold (-> "data/example-dolce.edn" slurp read-string)
        resources (owl/resource-ids conn)
        silver  (zipmap resources
                        (map #(res/pull-resource % conn) resources))
        gold-res   (-> gold keys set)
        silver-res (-> silver keys set)
        okay? (atom true)]
    (when (not= gold-res silver-res)
      (reset! okay? false)
      (when-let [gold-extra (not-empty (sets/difference gold-res silver-res))]
        (log/error "Correct has resources missing in test:" gold-extra))
      (when-let [silver-extra (not-empty (sets/difference silver-res gold-res))]
        (log/error "Test has resource not found in correct:" silver-extra))
      (when @okay?
        (doseq [res gold-res :while @okay?]
          (when (not= (res gold) (res silver))
            (reset! okay? false)
            (log/error "Difference in resource" res)
            (pprint (res gold))
            (pprint (res silver))))))
    @okay?))

(deftest medium-onto-okay
  (testing "Testing that a medium-sized ontology is captured correctly."
      (is (every? identity ((juxt make-medium-db d/database-exists?) medium-cfg)))
      (is (db-match? @medium-atm))))

;;;---------------------- Component tests --------------------------------------
(deftest check-resolve-rdf-lists []
  (testing "Testing that lists resolve correctly."
    (is (= #:temp{:t-301f5ece:17e06c5afb2:-77f6 [#:resource{:temp-ref :sem/s-communication-theory}]}
           (owl/resolve-rdf-lists
            [[:temp/t-301f5ece:17e06c5afb2:-77f7 :owl/oneOf :temp/t-301f5ece:17e06c5afb2:-77f6]
             [:temp/t-301f5ece:17e06c5afb2:-77f6 :rdf/rest :rdf/nil]
             [:temp/t-301f5ece:17e06c5afb2:-77f6 :rdf/first #:resource{:temp-ref :sem/s-communication-theory}]])))))

;;; Memory cleanup. I keep the file-based one around.
(doseq [db [medium-cfg info-cfg modal-cfg]]
  (when (d/database-exists? db)
    (d/delete-database db)))

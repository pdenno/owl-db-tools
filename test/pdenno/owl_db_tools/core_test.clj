(ns pdenno.owl-db-tools.core-test
  (:require
   [clojure.set           :as sets]
   [clojure.test :refer  [deftest is testing]]
   [clojure.pprint :refer [pprint]]
   [datahike.api          :as d]
   [datahike.pull-api     :as dp]
   [edu.ucdenver.ccp.kr.jena.kb]
   [pdenno.owl-db-tools.core :as owl]
   [pdenno.owl-db-tools.util :as util]
   [taoensso.timbre          :as log]))

(util/config-log :info)

;;; ---------------- Challenging small test case ----------------------------------
;;; This is challenging because it contains for example:
;;;   <owl:oneOf rdf:parseType="Collection">
;;;     <edns:theory rdf:about="http://www.ontologydesignpatterns.org/ont/dlp/SemioticCommunicationTheory.owl#s-communication-theory"/>
;;;   </owl:oneOf>
(def info-cfg {:store {:backend :mem :id "test"} :keep-history? false :schema-flexibility :write})
(def info-sources {"info"  {:uri "http://www.ontologydesignpatterns.org/ont/dlp/InformationObjects.owl"}})
(def info-user-attrs [#:db{:ident :testing/found? :cardinality :db.cardinality/one :valueType :db.type/boolean}])
(def info-atm nil)

(defn make-info-db [cfg]
  (when (d/database-exists? cfg) (d/delete-database cfg))
  (alter-var-root (var info-atm)
                  (fn [_]
                    (owl/create-db! cfg
                                    info-sources
                                    :user-attrs info-user-attrs
                                    :rebuild? true))))

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

(def modal-atm nil)

(defn make-modal-db [cfg]
  (when (d/database-exists? modal-cfg) (d/delete-database cfg))
  (alter-var-root (var modal-atm)
                  (fn [_]
                    (owl/create-db! cfg
                                    modal-sources
                                    :rebuild? true))))

(deftest modal-db-test ; ToDo: define more tests here to justify its existance.
  (testing "Testing that some attributes are learned and one more provided by the users is kept."
    (is (every? identity ((juxt make-modal-db d/database-exists?) modal-cfg)))))

;;; ---------------- Medium test case ---------------------------------- ; ToDo This isn't so medium!
(def medium-cfg {:store {:backend :mem :id "test"} :keep-history? false :schema-flexibility :write})
(def medium-atm nil)

(defn make-medium-db [cfg]
   (when (d/database-exists? cfg) (d/delete-database cfg))
  (alter-var-root (var medium-atm)
                  (fn [_]
                     (owl/create-db! cfg
                                     {"dol" {:uri "http://www.ontologydesignpatterns.org/ont/dlp/DOLCE-Lite.owl"}}
                                     :rebuild? true))))

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
             (pprint (owl/pull-resource obj @medium-atm)))
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
                        (map #(owl/pull-resource % conn) resources))
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

;;; ---------------- Comprehensive test case ----------------------------------
(def big-sources
  {"cause"  {:uri "http://www.ontologydesignpatterns.org/ont/dlp/Causality.owl"  :ref-only? true},
   "coll"   {:uri "http://www.ontologydesignpatterns.org/ont/dlp/Collections.owl"},
   "colv"   {:uri "http://www.ontologydesignpatterns.org/ont/dlp/Collectives.owl"},
   "common" {:uri "http://www.ontologydesignpatterns.org/ont/dlp/CommonSenseMapping.owl"},
   "dlp"    {:uri "http://www.ontologydesignpatterns.org/ont/dlp/DLP_397.owl"},
   "dol"    {:uri "http://www.ontologydesignpatterns.org/ont/dlp/DOLCE-Lite.owl"},
   "edns"   {:uri "http://www.ontologydesignpatterns.org/ont/dlp/ExtendedDnS.owl"},
   "fpar"   {:uri "http://www.ontologydesignpatterns.org/ont/dlp/FunctionalParticipation.owl"},
   "info"   {:uri "http://www.ontologydesignpatterns.org/ont/dlp/InformationObjects.owl"},
   "modal"  {:uri "http://www.ontologydesignpatterns.org/ont/dlp/ModalDescriptions.owl"},
   "plan"   {:uri "http://www.ontologydesignpatterns.org/ont/dlp/Plans.owl"},
   "sem"    {:uri "http://www.ontologydesignpatterns.org/ont/dlp/SemioticCommunicationTheory.owl" :ref-only? true},
   "space"  {:uri "http://www.ontologydesignpatterns.org/ont/dlp/SpatialRelations.owl"},
   "soc"    {:uri "http://www.ontologydesignpatterns.org/ont/dlp/SocialUnits.owl"},
   "sys"    {:uri "http://www.ontologydesignpatterns.org/ont/dlp/Systems.owl"},
   "time"   {:uri "http://www.ontologydesignpatterns.org/ont/dlp/TemporalRelations.owl"},

   "model"  {:uri "http://modelmeth.nist.gov/modeling",   :access "data/modeling.ttl",   :format :turtle},
   "ops"    {:uri "http://modelmeth.nist.gov/operations", :access "data/operations.ttl", :format :turtle}})

(def big-cfg {:store {:backend :file :path "/tmp/datahike-owl-db"}
              :keep-history? false
              :schema-flexibility :write})

(def big-atm (d/connect big-cfg))

(defn make-big-db [cfg]
  (when (d/database-exists? cfg) (d/delete-database cfg))
  (alter-var-root (var big-atm)
                  (fn [_]
                    (owl/create-db!
                     cfg
                     big-sources
                     :rebuild? true
                     :check-sites ["http://ontologydesignpatterns.org/wiki/Main_Page"]))))

;;; Temporary -- too slow 
#_(deftest big-onto-okay
  (testing "Read a substantial amount of owl; make a tiny check ;^)"
    (is (every? identity ((juxt make-big-db d/database-exists?) big-cfg)))
    (is (= [:dol/stative]
           (-> (owl/pull-resource :dol/state @big-atm) :rdfs/subClassOf)))))

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

;;;---------------------- Random investigations --------------------------------------
(defn verify-ref-idea
  "This probably follows directly from design of DH, but...
    (1) Verify that it is possible to avoid use of :resource/ref by resolving references.
    (2) Verify that the reference is made using just the integer, not {:db/id the-integer}."
  []
  (let [cfg  {:store {:backend :mem :id "test"} :keep-history? false :schema-flexibility :write}]
    (when (d/database-exists? cfg) (d/delete-database cfg))
    (d/create-database cfg)
    (d/transact (d/connect cfg)
                [#:db{:ident :class-a/id  :cardinality :db.cardinality/one :valueType :db.type/keyword :unique :db.unique/identity}
                 #:db{:ident :class-b/ref :cardinality :db.cardinality/one :valueType :db.type/ref}])
    (d/transact (d/connect cfg) [{:class-a/id :class-a-1}])
    (let [eid (d/q '[:find ?e . :where [?e :class-a/id :class-a-1]] @(d/connect cfg))]
      (d/transact (d/connect cfg) [{:class-b/ref eid}])
      #_(d/q '[:find ?e ?a ?v :where [?e ?a ?v]] @(d/connect cfg))
      (dp/pull-many @(d/connect cfg) '[*] [1 2 3 4]))))

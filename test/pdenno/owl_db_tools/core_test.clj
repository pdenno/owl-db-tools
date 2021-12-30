(ns pdenno.owl-db-tools.core-test
  (:require
   [clojure.test :refer  [deftest is testing]]
   [clojure.pprint :refer [pprint]]
   [datahike.api          :as d]
   [datahike.pull-api     :as dp]
   [edu.ucdenver.ccp.kr.jena.kb]
   [pdenno.owl-db-tools.core :as owl]
   [pdenno.owl-db-tools.util :as util]))

(util/config-log :info)

;;; ---------------- Small test case ----------------------------------
(def small-cfg {:store {:backend :mem :id "test"} :keep-history? false :schema-flexibility :write})

(def small-conn nil)

(defn run-small-conn []
   (when (d/database-exists? small-cfg) (d/delete-database small-cfg))
  (alter-var-root (var small-conn)
                  (fn [_]
                     (owl/create-db! small-cfg
                                     {"dol" {:uri "http://www.ontologydesignpatterns.org/ont/dlp/DOLCE-Lite.owl"}}
                                     :rebuild? true))))

(defn write-resources-map
  "Writes a file data/example-dolce.edn used in testing testing.
   It is NOT executed in testing."
  []
  (let [sorted-resources (-> (d/q '[:find [?v ...] :where [_ :resource/id ?v]] @small-conn) sort)]
    (->> (with-out-str
           (println "{")
           (doseq [obj sorted-resources]
             (println "\n\n" obj)
             (pprint (owl/pull-resource obj @small-conn)))
           (println "}"))
         (spit "data/example-dolce.edn"))))

(deftest small-onto-okay
  (testing "that a small-sized ontology is captured correctly."
    (let [resources (d/q '[:find [?v ...] :where [_ :resource/id ?v]] @small-conn)]
      (is (do (run-small-conn) (d/database-exists? small-cfg)))
      (is (= (-> "data/example-dolce.edn" slurp read-string)
             (zipmap resources
                     (map #(owl/pull-resource % @small-conn) resources)))))))

;;; ---------------- Challenging small test case ----------------------------------
;;; This is challenging because it contains for example:
;;;   <owl:oneOf rdf:parseType="Collection">
;;;     <edns:theory rdf:about="http://www.ontologydesignpatterns.org/ont/dlp/SemioticCommunicationTheory.owl#s-communication-theory"/>
;;;   </owl:oneOf>

(def info-cfg {:store {:backend :mem :id "test"} :keep-history? false :schema-flexibility :write})

(def info-sources
  {"info"  {:uri "http://www.ontologydesignpatterns.org/ont/dlp/InformationObjects.owl"}})

(def info-conn nil)

(defn run-info-conn []
  (when (d/database-exists? info-cfg) (d/delete-database info-cfg))
  (alter-var-root (var info-conn)
                  (fn [_]
                    (owl/create-db! info-cfg
                                    info-sources
                                    :rebuild? true))))

;;; ---------------- Modal test case ----------------------------------
(def modal-cfg {:store {:backend :mem :id "test"} :keep-history? false :schema-flexibility :write})

(def modal-sources
  {"edns"  {:uri "http://www.ontologydesignpatterns.org/ont/dlp/ExtendedDnS.owl"},
   "modal" {:uri "http://www.ontologydesignpatterns.org/ont/dlp/ModalDescriptions.owl"}})

(def modal-conn nil)

(defn run-modal-conn []
  (when (d/database-exists? modal-cfg) (d/delete-database modal-cfg))
  (alter-var-root (var modal-conn)
                  (fn [_]
                    (owl/create-db! modal-cfg
                                    modal-sources
                                    :rebuild? true))))

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
   "mod"    {:uri "http://www.ontologydesignpatterns.org/ont/dlp/ModalDescriptions.owl"},
   "pla"    {:uri "http://www.ontologydesignpatterns.org/ont/dlp/Plans.owl"},
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

(def big-conn nil)

(defn run-big-conn []
  (when (d/database-exists? big-cfg) (d/delete-database big-cfg))
  (alter-var-root (var big-conn)
                  (fn [_]
                    (owl/create-db!
                     big-cfg
                     big-sources
                     :rebuild? true
                     :check-sites ["http://ontologydesignpatterns.org/wiki/Main_Page"]))))

(deftest big-onto-okay
  (testing "Read a substantial amount of owl; make a tiny check ;^)"
    (is (do (run-big-conn) (d/database-exists? big-cfg)))
    (is (= [:dol/stative]
           (-> (owl/pull-resource :dol/state @big-conn) :rdfs/subClassOf)))))

(deftest check-resolve-rdf-lists []
  (testing "that lists resolve correctly"
    (is (= #:temp{:t-301f5ece:17e06c5afb2:-77f6 [#:resource{:temp-ref :sem/s-communication-theory}]}
           (owl/resolve-rdf-lists
            [[:temp/t-301f5ece:17e06c5afb2:-77f7 :owl/oneOf :temp/t-301f5ece:17e06c5afb2:-77f6]
             [:temp/t-301f5ece:17e06c5afb2:-77f6 :rdf/rest :rdf/nil]
             [:temp/t-301f5ece:17e06c5afb2:-77f6 :rdf/first #:resource{:temp-ref :sem/s-communication-theory}]])))))

;;; Memory cleanup. I keep the file-based one around.
(doseq [db [small-cfg info-cfg modal-cfg]]
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

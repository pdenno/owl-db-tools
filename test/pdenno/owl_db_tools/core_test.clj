(ns pdenno.owl-db-tools.core-test
  (:require
   [clojure.test :refer  [deftest is testing]]
   [clojure.pprint :refer [pprint]]
   [datahike.api          :as d]
   [datahike.pull-api     :as dp]
   [pdenno.owl-db-tools.core :as owl]))

;;; ToDo:
;;;   - Determine what happened to DLP "cause" "sem"
;;;      Answer for "sem": triples exist  [:sem/code-role :edns/defined-by :sem/s-communication-theory]

;;; ---------------- Small test case ----------------------------------
(def small-cfg {:store {:backend :mem :id "small-test"} :keep-history? false :schema-flexibility :write})

(defonce small-conn
  (do (when (d/database-exists? small-cfg) (d/delete-database small-cfg))
      (owl/create-db! small-cfg
                      {"dol" {:uri "http://www.ontologydesignpatterns.org/ont/dlp/DOLCE-Lite.owl"}}
                      :rebuild? true)))

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

(deftest dolce-okay
  (testing "that a medium-sized ontology is captured correctly."
    (let [resources (d/q '[:find [?v ...] :where [_ :resource/id ?v]] @small-conn)]
      (is (= (-> "data/example-dolce.edn" slurp read-string)
             (zipmap resources
                     (map #(owl/pull-resource % @small-conn) resources)))))))

;;; ---------------- Challenging small test case ----------------------------------
;;; This is challenging because it contains for example:
;;;   <owl:oneOf rdf:parseType="Collection">
;;;     <edns:theory rdf:about="http://www.ontologydesignpatterns.org/ont/dlp/SemioticCommunicationTheory.owl#s-communication-theory"/>
;;;   </owl:oneOf>

(def info-cfg {:store {:backend :mem :id "info-test"} :keep-history? false :schema-flexibility :write})

(def info-sources
  {"cause" {:uri "http://www.ontologydesignpatterns.org/ont/dlp/Causality.owl"  :ref-only? true},
   "coll"  {:uri "http://www.ontologydesignpatterns.org/ont/dlp/Collections.owl" :ref-only? true},
   "colv"  {:uri "http://www.ontologydesignpatterns.org/ont/dlp/Collectives.owl" :ref-only? true},
   "cs"    {:uri "http://www.ontologydesignpatterns.org/ont/dlp/CommonSenseMapping.owl" :ref-only? true},
   "dlp"   {:uri "http://www.ontologydesignpatterns.org/ont/dlp/DLP_397.owl" :ref-only? true},
   "dol"   {:uri "http://www.ontologydesignpatterns.org/ont/dlp/DOLCE-Lite.owl" :ref-only? true},
   "edns"  {:uri "http://www.ontologydesignpatterns.org/ont/dlp/ExtendedDnS.owl" :ref-only? true},
   "fpar"  {:uri "http://www.ontologydesignpatterns.org/ont/dlp/FunctionalParticipation.owl" :ref-only? true},
   "info"  {:uri "http://www.ontologydesignpatterns.org/ont/dlp/InformationObjects.owl"}, ;<==========
   "modal" {:uri "http://www.ontologydesignpatterns.org/ont/dlp/ModalDescriptions.owl" :ref-only? true},
   "plan"  {:uri "http://www.ontologydesignpatterns.org/ont/dlp/Plans.owl" :ref-only? true},
   "sem"   {:uri "http://www.ontologydesignpatterns.org/ont/dlp/SemioticCommunicationTheory.owl" :ref-only? true},
   "space" {:uri "http://www.ontologydesignpatterns.org/ont/dlp/SpatialRelations.owl" :ref-only? true},
   "soc"   {:uri "http://www.ontologydesignpatterns.org/ont/dlp/SocialUnits.owl" :ref-only? true},
   "sys"   {:uri "http://www.ontologydesignpatterns.org/ont/dlp/Systems.owl" :ref-only? true},
   "time"  {:uri "http://www.ontologydesignpatterns.org/ont/dlp/TemporalRelations.owl" :ref-only? true}})

(def info-conn
  (do (when (d/database-exists? small-cfg) (d/delete-database info-cfg))
      (owl/create-db! info-cfg
                      info-sources
                      :rebuild? true)))

;;; ---------------- Comprehensive  test case ----------------------------------
(def onto-sources
  {"cause" {:uri "http://www.ontologydesignpatterns.org/ont/dlp/Causality.owl"  :ref-only? true},
   "coll"  {:uri "http://www.ontologydesignpatterns.org/ont/dlp/Collections.owl"},
   "colv"  {:uri "http://www.ontologydesignpatterns.org/ont/dlp/Collectives.owl"},
   "cs"    {:uri "http://www.ontologydesignpatterns.org/ont/dlp/CommonSenseMapping.owl"},
   "dlp"   {:uri "http://www.ontologydesignpatterns.org/ont/dlp/DLP_397.owl"},
   "dol"   {:uri "http://www.ontologydesignpatterns.org/ont/dlp/DOLCE-Lite.owl"},
   "edns"  {:uri "http://www.ontologydesignpatterns.org/ont/dlp/ExtendedDnS.owl"},
   "fpar"  {:uri "http://www.ontologydesignpatterns.org/ont/dlp/FunctionalParticipation.owl"},
   "info"  {:uri "http://www.ontologydesignpatterns.org/ont/dlp/InformationObjects.owl"},
   "modal" {:uri "http://www.ontologydesignpatterns.org/ont/dlp/ModalDescriptions.owl"},
   "plan"  {:uri "http://www.ontologydesignpatterns.org/ont/dlp/Plans.owl"},
   "sem"   {:uri "http://www.ontologydesignpatterns.org/ont/dlp/SemioticCommunicationTheory.owl" :ref-only? true},
   "space" {:uri "http://www.ontologydesignpatterns.org/ont/dlp/SpatialRelations.owl"},
   "soc"   {:uri "http://www.ontologydesignpatterns.org/ont/dlp/SocialUnits.owl"},
   "sys"   {:uri "http://www.ontologydesignpatterns.org/ont/dlp/Systems.owl"},
   "time"  {:uri "http://www.ontologydesignpatterns.org/ont/dlp/TemporalRelations.owl"},
   
   "mod"   {:uri "http://modelmeth.nist.gov/modeling",   :access "data/modeling.ttl",   :format :turtle},
   "ops"   {:uri "http://modelmeth.nist.gov/operations", :access "data/operations.ttl", :format :turtle}})

(def big-cfg {:store {:backend :file :path "/tmp/datahike-owl-db"}
              :keep-history? false
              :schema-flexibility :write})

(def big-conn
  (do (when (d/database-exists? big-cfg) (d/delete-database big-cfg))
      (owl/create-db!
       big-cfg
       onto-sources
       :rebuild? true
       :check-sites ["http://ontologydesignpatterns.org/wiki/Main_Page"])))

(deftest reading-owl
  (testing "Read a substantial amount of owl."
    (is (d/database-exists? big-cfg))
    (is (= [:dol/stative]
           (-> (owl/pull-resource :dol/state @big-conn) :rdfs/subClassOf)))))


;;;---------------------- Random investigations --------------------------------------
(defn verify-ref-idea
  "This probably follows directly from design of DH, but...
    (1) Verify that it is possible to avoid use of :resource/ref by resolving references. 
    (2) Verify that the reference is made using just the integer, not {:db/id the-integer}."
  []
  (let [cfg  {:store {:backend :mem :id "exp1"} :keep-history? false :schema-flexibility :write}]
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


(def edns-data
  "<owl:oneOf rdf:parseType=\"Collection\">
     <edns:theory rdf:about=\"http://www.ontologydesignpatterns.org/ont/dlp/SemioticCommunicationTheory.owl#s-communication-theory\"/>
</owl:oneOf>")

(defn tryme []
  (owl/load-jena))

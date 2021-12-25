(ns pdenno.owl-db-tools.core-test
  (:require
   [clojure.test :refer  [deftest is testing]]
   [clojure.pprint :refer [pprint]]
   [datahike.api          :as d]
   [pdenno.owl-db-tools.core :as owl]))

;;; ToDo:
;;;   - Determine what happened to DLP "cause" "sem"
;;;      Answer for "sem": triples exist  [:sem/code-role :edns/defined-by :sem/s-communication-theory]

(def db-cfg {:store {:backend :file :path "/tmp/datahike-owl-db"}
             :keep-history? false
             :schema-flexibility :write})

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

(deftest reading-owl
  (testing "Read a substantial amount of owl."
     (owl/create-db! db-cfg onto-sources
                     :rebuild? true
                     :check-sites ["http://ontologydesignpatterns.org/wiki/Main_Page"])
     (let [conn (owl/create-db! db-cfg nil)]
       (is (= (d/database-exists? db-cfg) true))
       (is (= [:dol/stative]
              (-> (owl/pull-resource :dol/state conn) :rdfs/subClassOf))))))

(defn small-test
  []
  (owl/create-db!
   {:store {:backend :mem} :keep-history? false :schema-flexibility :write}
   {"dol" {:uri "http://www.ontologydesignpatterns.org/ont/dlp/DOLCE-Lite.owl"}}
   :rebuild? true))

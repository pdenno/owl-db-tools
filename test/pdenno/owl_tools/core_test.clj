(ns pdenno.owl-tools.core-test
  (:require
   [clojure.test :refer  [deftest is testing]]
   [pdenno.owl-tools.core :as owl]))

;;; ToDo:
;;;   - Determine what happened to "cause" "sem" (see missing-ontology?)
;;;      Answer: "sem" triples exist:  [:sem/code-role :edns/defined-by :sem/s-communication-theory]

(def db-cfg {:store {:backend :file :path "database"}
             :mine/rebuild-db? true
             :keep-history? false
             :schema-flexibility :write})

(def ontologies-mine
  "The key of this map is the shortname of the namespace, the value is a map of things needed to read it
   (if it is going to be read)."
  {;; Mine
   "ops"   {:prefix "http://modelmeth.nist.gov/operations"
            :access "data/operations.ttl"
            :format :turtle :in-resources? true}

   "mod"   {:prefix "http://modelmeth.nist.gov/modeling"
            :access "data/modeling.ttl"
            :format :turtle :in-resources? true}})

;;; 1256 resources (was 1595). 
;;; 5888 triples. Still! (2021-07-25). 
(def ontologies-dolce-list
   ;; DOLCE Lite Plus
   [["dlp"   "http://www.ontologydesignpatterns.org/ont/dlp/DLP_397.owl"]
    ["coll"  "http://www.ontologydesignpatterns.org/ont/dlp/Collections.owl"]
    ["colv"  "http://www.ontologydesignpatterns.org/ont/dlp/Collectives.owl"]
    ["sys"   "http://www.ontologydesignpatterns.org/ont/dlp/Systems.owl"]
    ;; aldo.gangemi@unibo.it -- I asked for an ID on the Ontology Design Patterns group; he's a chair. 
    ;; (slurp "http://www.ontologydesignpatterns.org/ont/dlp/Causality.owl")
    ["sem"   "http://www.ontologydesignpatterns.org/ont/dlp/SemioticCommunicationTheory.owl"] ; not a file 
    ["cause" "http://www.ontologydesignpatterns.org/ont/dlp/Causality.owl"]                   ; not a file
    ["soc"   "http://www.ontologydesignpatterns.org/ont/dlp/SocialUnits.owl"]
    ["space" "http://www.ontologydesignpatterns.org/ont/dlp/SpatialRelations.owl"]
    ["info"  "http://www.ontologydesignpatterns.org/ont/dlp/InformationObjects.owl"]
    ["plan"  "http://www.ontologydesignpatterns.org/ont/dlp/Plans.owl"]
    ["cs"    "http://www.ontologydesignpatterns.org/ont/dlp/CommonSenseMapping.owl"]
    ["time"  "http://www.ontologydesignpatterns.org/ont/dlp/TemporalRelations.owl"]
    ["edns"  "http://www.ontologydesignpatterns.org/ont/dlp/ExtendedDnS.owl"] ; DnS = Descriptions and Situations. 
    ["fpar"  "http://www.ontologydesignpatterns.org/ont/dlp/FunctionalParticipation.owl"]
    ["modal" "http://www.ontologydesignpatterns.org/ont/dlp/ModalDescriptions.owl"]
   ;; DOLCE Lite
    ["dol"   "http://www.ontologydesignpatterns.org/ont/dlp/DOLCE-Lite.owl"]])

(def rdf-ordinary-list
   [["dc"    "http://purl.org/dc/elements/1.1/"]
    ["daml"  "http://www.daml.org/2001/03/daml+oil"]
    ["owl"   "http://www.w3.org/2002/07/owl"]
    ["rdf"   "http://www.w3.org/1999/02/22-rdf-syntax-ns"]
    ["xml"   "http://www.w3.org/XML/1998/namespace"]
    ["xsd"   "http://www.w3.org/2001/XMLSchema"]
    ["rdfs"  "http://www.w3.org/2000/01/rdf-schema"]])

;;; POD check if SemioticCommunicationTheory.owl and Cauality.owl are just namespaces!
(def missing-ontology?
  "The following are files that can't be found at their URI. Perhaps they are just namespaces."
  #{"sem" "cause"}) ; That is the case for "sem"; not sure about "cause"!

(def onto-sources
  "This is used for both loading ontologies and producing short resource names using xmlns prefixes."
  (merge ontologies-mine
         (reduce (fn [m [s l]]
                   (if (missing-ontology? s)
                     (assoc m s {:prefix l #_(str l "#")})
                     (assoc m s {:prefix l #_(str l "#") :format :rdfxml :access l})))
                 {}
                 ontologies-dolce-list)
         (reduce (fn [m [s l]]
                   (assoc m s {:prefix l}))
                 {}
                 rdf-ordinary-list)))

(deftest reading-owl
  (testing "Read a substantial amount of owl."
    (is (= :success (owl/create-db! db-cfg onto-sources)))))

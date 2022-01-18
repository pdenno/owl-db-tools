(ns pdenno.owl-db-tools.resolvers-test
  "Pathom3 resolvers to make accessing the DB easy."
  (:require
   [clojure.string             :as str]
   [clojure.test :refer  [deftest is testing]]
   [com.wsscode.misc.macros    :as pmacs]
   [com.wsscode.pathom3.connect.built-in.resolvers :as pbir]
   [com.wsscode.pathom3.connect.built-in.plugins :as pbip]
   [com.wsscode.pathom3.connect.indexes :as pci]
   [com.wsscode.pathom3.connect.operation :as pco]
   [com.wsscode.pathom3.interface.eql :as p.eql]
   [com.wsscode.pathom3.interface.smart-map :as psm]
   [datahike.api                  :as d]
   [datahike.pull-api             :as dp]
   [edn-query-language.core       :as eql] ; Nice for experimentation
   [pdenno.owl-db-tools.core      :as owl]
   [pdenno.owl-db-tools.resolvers :as res]
   [taoensso.timbre               :as log]))

(def big-cfg {:store {:backend :file :path "/tmp/datahike-owl-db"}
              #_#_:keep-history? false
              #_#_:schema-flexibility :write})

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

(def big-atm (d/connect big-cfg))

(def conn @big-atm)

(defn make-big-db [cfg]
  (when (d/database-exists? cfg) (d/delete-database cfg))
  (alter-var-root (var big-atm)
                  (fn [_]
                    (owl/create-db!
                     cfg
                     big-sources
                     :rebuild? true
                     :check-sites ["http://ontologydesignpatterns.org/wiki/Main_Page"]))))

;;;============================== Experimentation with Pathom3 and Learning =============================
;;; (resource-by-db-id {:db/id 629}) ; ==> The whole :info/mapped-to entity. Not currently used.
(pco/defresolver resource-by-db-id [{:db/keys [id]}]
  {:app/resource (dp/pull conn '[*] id)})

;;; (uri-to-db-id {:resource/rdf-uri :info/mapped-to}) ; ==> #:db{:id 629} Not currently used.
(pco/defresolver uri-to-db-id [{:resource/keys [rdf-uri]}]
  {::pco/output [:db/id]}
  (dp/pull conn [:db/id] [:resource/id rdf-uri]))

;;; (resource-by-uri {:resource/rdf-uri :info/mapped-to}) ; ==> The whole :info/mapped-to entity.
(pco/defresolver resource-by-uri [{:resource/keys [rdf-uri]}]
  {:resource/body (dp/pull conn '[*] [:resource/id rdf-uri])}) ; lookup (ident style) of pull.

(def resolver-answer
  #:resource{:attr
             #:rdfs{:comment
                    [(str "Any entity e1 is mapped to any other entity e2 when one of the concepts that classify e1, "
                          "e.g. c1, is contextually augmented by another concept c2 from either the same description "
                          "as c1 (metonymy), or from another description (metaphor).")]}})

(deftest resolvers-work
  (testing "Testing that resolvers seem to work"
    (let [body {:input [:resource/rdf-uri],
                :output [:resource/attr],
                :fn (fn [_env {:resource/keys [rdf-uri]}] {:resource/attr (dp/pull conn '[:rdfs/comment] [:resource/id rdf-uri])})}
          res-1 (res/make-resolver 'test-get-comment-1 body)
          res-2 (res/make-resolver 'test-get-comment-2 (assoc body :fn (res/attr-fn :rdfs/comment false)))]
      (is (= resolver-answer (res-1 nil {:resource/rdf-uri :info/mapped-to})))
      (is (= resolver-answer (res-2 nil {:resource/rdf-uri :info/mapped-to}))))))

;;; I conclude that I can't do what I want without PARAMETERS!
;;; (p.eql/process index [{[:resource/rdf-uri :info/mapped-to] ['(:resource/attr {:attr/name :rdfs/domain})]}])
;;; (p.eql/process index [{[:resource/rdf-uri :info/mapped-to] ['(:resource/attr {:attr/name :rdfs/comment})]}])
(pco/defresolver resource-attr  [env {:resource/keys [body]}]
  {::pco/output [{:resource/attr [:attr/body :attr/name]}]} ; attr/name will be provided as a PARAMETER.
  {:resource/attr
   (letfn [(subobj [x]
             (cond (and (map? x) (contains? x :resource/id)) (:resource/id x),          ; It is a whole resource, return ref.
                   (and (map? x) (contains? x :db/id) (== (count x) 1))                 ; It is an object ref...
                   (or (and (map? x)
                            (contains? x :db/id)
                            (d/q `[:find ?id . :where [~(:db/id x) :resource/id ?id]] conn)) ; ...return keyword if it is a resource...
                       (subobj (dp/pull conn '[*] (:db/id x)))),                             ; ...otherwise it is some other structure.
                   (map? x) (reduce-kv (fn [m k v] (assoc m k (subobj v))) {} x),
                   (vector? x) (mapv subobj x),
                   :else x))]
     (when-let [attr (get (pco/params env) :attr/name)] ; Retrieve parameter.
       {:attr/body (subobj (get body attr))}))})

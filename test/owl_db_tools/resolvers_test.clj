(ns owl-db-tools.resolvers-test
  "Testing auto-created Pathom3 resolvers."
  (:require
   [clojure.test :refer  [deftest is testing]]
   ;[com.wsscode.pathom3.interface.eql :as p.eql]
   [datahike.api                  :as d]
   [datahike.pull-api             :as dp]
   [owl-db-tools.core      :as owl :refer [*conn*]]
   [owl-db-tools.resolvers :as res]))

(def big-cfg {:store {:backend :file :path (str (System/getenv "HOME") "/Databases/datahike-owl-db")}
              :keep-history? false
              :schema-flexibility :write})

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
   "pla"    {:uri "http://www.ontologydesignpatterns.org/ont/dlp/Plans.owl"},
   "sem"    {:uri "http://www.ontologydesignpatterns.org/ont/dlp/SemioticCommunicationTheory.owl" :ref-only? true},
   "space"  {:uri "http://www.ontologydesignpatterns.org/ont/dlp/SpatialRelations.owl"},
   "soc"    {:uri "http://www.ontologydesignpatterns.org/ont/dlp/SocialUnits.owl"},
   "sys"    {:uri "http://www.ontologydesignpatterns.org/ont/dlp/Systems.owl"},
   "time"   {:uri "http://www.ontologydesignpatterns.org/ont/dlp/TemporalRelations.owl"},

   "model"  {:uri "http://modelmeth.nist.gov/modeling",   :access "data/modeling.ttl",   :format :turtle},
   "ops"    {:uri "http://modelmeth.nist.gov/operations", :access "data/operations.ttl", :format :turtle}})

(defn make-big-db
  "This establishes the DB and sets *conn*. It takes a minute or two."
  [cfg]
  (when (d/database-exists? cfg) (d/delete-database cfg))
  (owl/create-db!
   cfg
   big-sources
   :rebuild? true
   :check-sites ["http://ontologydesignpatterns.org/wiki/Main_Page"]))

(defn save-big-db-files
  "I used this one time (arg sources = big sources) to copy the DLP files to a local directory."
  [sources]
  (as-> sources ?s
       (reduce-kv (fn [m k v]
                    (if (or (->> v :uri (re-matches #".*nist\.gov.*"))
                            (-> v :ref-only?))
                      m
                      (assoc m (str k ".owl") (:uri v))))
                  {} ?s)
       (doall (reduce-kv (fn [_m k v]
                           (->> v
                                slurp
                                (spit (str "data/DLP/" k))))
                         {}
                         ?s))))

;;; Run the following once to establish the DB. It might take a few minutes.
;;; (make-big-db big-cfg)

(def resolver-answer
  {:rdfs/comment
   [(str "Any entity e1 is mapped to any other entity e2 when one of the concepts that classify e1, "
         "e.g. c1, is contextually augmented by another concept c2 from either the same description "
         "as c1 (metonymy), or from another description (metaphor).")]})

(deftest datahike-queries
  (testing "That direct queries of the database work."))

(deftest resolvers-work
  (testing "Testing that resolvers work"
    (binding [*conn* @(d/connect big-cfg)]
      (let [test-db (res/register-resolvers! *conn*)
            body {:input [:resource/iri],
                  :output [:resource/attr],
                  :fn (fn [_env {:resource/keys [iri]}] {:resource/attr (dp/pull *conn* '[:rdfs/comment] [:resource/iri iri])})}
            res-1 (res/make-resolver 'test-get-comment-1 body)
            res-2 (res/make-resolver 'test-get-comment-2 (assoc body :fn (res/attr-fn :rdfs/comment false)))]
        (is (= resolver-answer (:resource/attr (res-1 nil {:resource/iri :info/mapped-to}))))
        (is (= resolver-answer                 (res-2 nil {:resource/iri :info/mapped-to})))
        (is (= resolver-answer (get (test-db [{[:resource/iri :info/mapped-to] [:rdfs/comment]}])
                                    [:resource/iri :info/mapped-to])))))))

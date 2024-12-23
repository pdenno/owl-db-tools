(ns owl-db-tools.resolvers-test
  "Testing auto-created Pathom3 resolvers Note comment below about the need to build the DB before testing."
  (:require
   [arachne.aristotle             :as aa]
   [arachne.aristotle.graph       :as g]
   [arachne.aristotle.query       :as q]
   [arachne.aristotle.registry    :as reg]
   [clojure.pprint                :refer [cl-format pprint]]
   [clojure.edn                   :as edn]
   [clojure.java.io               :as io]
   [clojure.test :refer  [deftest is testing]]
   ;;[com.wsscode.pathom3.interface.eql :as p.eql]
   ;;[edu.ucdenver.ccp.kr.jena.kb] ; This was added in 2024, following the pattern in core_test, that was before 2024.
   [datahike.api                  :as d]
   [datahike.pull-api             :as dp]
   [owl-db-tools.core      :as core :refer [*conn*]]
   [owl-db-tools.resolvers :as res]
   [taoensso.telemere       :as tel :refer [log!]]))

;;; THIS is the namespace I am hanging out in recently.
(def ^:diag diag (atom nil))

(def alias? (atom (-> (ns-aliases *ns*) keys set)))

(defn ^:diag ns-start-over!
  "This one has been useful. If you get an error evaluate this ns, (the declaration above) run this and try again."
  []
  (map (partial ns-unalias *ns*) (keys (ns-aliases *ns*))))

(defn ^:diag remove-alias
  "This one has NOT been useful!"
  [al ns-sym]
  (swap! alias? (fn [val] (->> val (remove #(= % al)) set)))
  (ns-unalias (find-ns ns-sym) al))

(defn safe-alias
  [al ns-sym]
  (when (and (not (@alias? al))
             (find-ns ns-sym))
    (alias al ns-sym)))

(defn ^:diag ns-setup!
  "Use this to setup useful aliases for working in this NS."
  []
  (ns-start-over!)
  (reset! alias? (-> (ns-aliases *ns*) keys set))
  (safe-alias 's      'clojure.spec.alpha)
  (safe-alias 'io     'clojure.java.io)
  (safe-alias 'str    'clojure.string)
  (safe-alias 'd      'datahike.api)
  (safe-alias 'dp     'datahike.pull-api)
  (safe-alias 'core   'owl-db-tools.core)
  (safe-alias 'res    'owl-db-tools.resolvers)
  (safe-alias 'util   'owl-db-tools.util))

;;; In order to run these tests, first time, it is necessary to create the directory below and run make-big-db, also below.
;;; (make-big-db big-cfg)

(def big-cfg {:store {:backend :file :path (str (System/getenv "HOME") "/Databases/datahike-owl-db")}
              :keep-history? false
              :schema-flexibility :write})

(def big-sources (-> "project-ontologies.edn" io/resource slurp edn/read-string))

;;;================================== aristotle stuff =======================================
;;;================================== end aristotle stuff =================================

;;; (make-big-db big-cfg)
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

#_(deftest datahike-queries
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

;;; Last I used this, it was for the original 'drlivingston/kr' DB.
(defn backup-whole-db!
  "Write the whole big-cfg DB to a file with pull API. No provisions to read it back it back."
  []
  (with-open [out (io/writer "data/big-cfg-out.edn")]
    (doseq [obj (dp/pull-many @(d/connect big-cfg) '[*] (range 1 1350))]
      (cl-format out "~A" (with-out-str (pprint obj))))))

;;; I used this to output intermediate stuff from aristotle.
;;; (backup-triples (q/run @core/graph-memo '[:bgp [?x ?y ?z]]))
(defn backup-triples
  "Write the triples to edn."
  [triples]
  (with-open [out (io/writer "data/aristotle-triples.edn")]
    (cl-format out "~%[")
    (doseq [obj triples] (cl-format out "~%~A" (with-out-str (pprint obj))))
    (cl-format out "~%]")))

;;; (backup-finished-triples @core/diag)
(defn backup-finished-maps
  "Write the dmapv triples to edn."
  [triples]
  (with-open [out (io/writer "data/finished-maps.edn")]
    (cl-format out "~%[")
    (doseq [obj triples] (cl-format out "~%~A" (with-out-str (pprint obj))))
    (cl-format out "~%]")))


(def ggg (atom nil))

(defn tryme []
  (let [graph (atom nil)
        conn @(d/connect big-cfg)] ; Later!
    #_(-> "project-ontologies.edn" io/resource slurp edn/read-string (core/load-graph! ggg))
    (core/prefix-maps conn)))

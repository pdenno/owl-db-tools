(ns pdenno.owl-db-tools.resolvers
  "Pathom3 resolvers to make accessing the DB easy."
  (:require
   [clojure.set                :as set]
   [clojure.string             :as str]
   [com.wsscode.misc.macros    :as pmacs]
   [com.wsscode.pathom3.connect.built-in.resolvers :as pbir]
   [com.wsscode.pathom3.connect.built-in.plugins :as pbip]
   [com.wsscode.pathom3.connect.indexes :as pci]
   [com.wsscode.pathom3.connect.operation :as pco]
   [com.wsscode.pathom3.interface.eql :as p.eql]
   [com.wsscode.pathom3.interface.smart-map :as psm]
   [datahike.api               :as d]
   [datahike.pull-api          :as dp]
   [edn-query-language.core    :as eql] ; Nice for experimentation
   [pdenno.owl-db-tools.core   :as owl]
   [pdenno.owl-db-tools.util   :as util]
   [taoensso.timbre            :as log]))

(def conn-atm (d/connect {:store {:backend :file :path "/tmp/datahike-owl-db"}}))

(def attr-info "ToDo: will probably have to be a function."
  (d/q '[:find  ?id ?type
         :keys attr/id attr/type
         :where [?e :db/ident ?id] [?e :db/valueType ?type]]
       @conn-atm))

(defn resource-attrs
  "Return the set of DB attributes that appear directly in resources."
  [conn]
  (-> (reduce (fn [res m] (into res (keys m))) #{} (util/pull-rand :resource/id 50 conn))
      (set/difference #{:db/id :resource/id})))

(defn make-resolver [sym {:keys [input output fn]}]
  (let [op-name (pmacs/full-symbol sym (str *ns*))]
    (pco/resolver
     `{::pco/input   ~input,
       ::pco/output  ~output
       ::pco/op-name ~op-name,
       ::pco/resolve ~fn})))

(defn attr-fn
  "Return a function for the argument.
   the dp/pull returns wrapped in the output Pathom requires."
  [attr resolve?]
  (if resolve?
    (fn [_env {:resource/keys [rdf-uri]}]
      (when-let [conn @conn-atm]
         (-> (dp/pull conn [attr] [:resource/id rdf-uri])
             (util/resolve-obj conn))))
    (fn [_env {:resource/keys [rdf-uri]}]
      (when-let [conn @conn-atm]
        (dp/pull conn [attr] [:resource/id rdf-uri])))))

(defn make-resource-attr-resolvers
  "Return a vector of resolvers for rdf resource attributes."
  [conn]
  (let [attrs (resource-attrs conn)]
    (vec (for [att attrs]
           (let [ref? (= :db.type/ref (some #(when (= (:attr/id %) att) (:attr/type %)) attr-info))]
             (make-resolver (symbol (str "rdf-uri-to-" (namespace att) "-" (name att)))
                            {:input [:resource/rdf-uri],
                             :output [att],
                             :fn (attr-fn att ref?)}))))))

(pco/defresolver uri-to-db-id [{:resource/keys [rdf-uri]}]
  {::pco/output [:db/id]}
  (dp/pull @conn-atm [:db/id] [:resource/id rdf-uri]))

;;; It looks like it is giving the same function to multiple resolvers <=======================
;;; See example problem in resolvers_test.clj                          <=======================
;;; (p.eql/process index [{[:resource/rdf-uri :info/mapped-to] [:rdfs/comment]}]) ;---- NG
;;; ((second index) nil {:resource/rdf-uri :info/mapped-to})                      ;---- OK
;;; I think I want the ::pco/output to be the attr!
;;; ALSO THIS:  :provides #:edns{:defined-by {}},                      <======================= What would it cost to do them all?
(def index (into [uri-to-db-id] (make-resource-attr-resolvers @conn-atm)))

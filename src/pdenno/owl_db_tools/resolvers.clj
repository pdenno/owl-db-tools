(ns pdenno.owl-db-tools.resolvers
  "Pathom3 resolvers to make accessing the DB easy."
  (:require
   [com.wsscode.misc.macros    :as pmacs]
   [com.wsscode.pathom3.connect.built-in.resolvers :as pbir]
   [com.wsscode.pathom3.connect.indexes :as pci]
   [com.wsscode.pathom3.connect.operation :as pco]
   [com.wsscode.pathom3.interface.eql :as p.eql]
   [datahike.api               :as d]
   [datahike.pull-api          :as dp]
   [pdenno.owl-db-tools.core   :as owl :refer [*conn*]]
   [pdenno.owl-db-tools.util   :as util]))

(defn attr-info
  "Return a vector of maps containing :attr/id and :attr/type
   for every attribute in the DB."
  [conn]
  (d/q '[:find  ?id ?type
         :keys attr/id attr/type
         :where [?e :db/ident ?id] [?e :db/valueType ?type]]
       conn))

(defn attr-types
  "Return map of two sets (with the following two keys):
    - :resource? :  DB attributes that appear directly in resources.
    - :experssion? : DB attributes that do not appear directly in resources."
  [conn]
  (->> (d/q '[:find [?attr ...] :where [_ ?attr _]] conn)
       (remove (fn [x] (#{"source" "app" "box" "db"} (namespace x)))) ; ToDo handle some of these.
       (reduce (fn [res attr]
                 (if (d/q `[:find ?x . :where [?x ~attr] [?x :resource/iri]] *conn*)
                   (update res   :resource? conj attr)
                   (update res :expression? conj attr)))
               {:expression? #{} :resource? #{}})))

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
    (fn [_env {:resource/keys [iri]}]
      (when *conn*
         (-> (dp/pull *conn* [attr] [:resource/iri iri])
             (util/resolve-obj *conn*))))
    (fn [_env {:resource/keys [iri]}]
      (when *conn*
        (dp/pull *conn* [attr] [:resource/iri iri])))))

(defn make-resource-attr-resolvers
  "Return a vector of resolvers for rdf resource attributes."
  [conn]
  (let [attrs (-> (attr-types conn) :resource? vec sort) ; sort for easier debugging.
        attr-types (attr-info conn)]
    (vec (for [att attrs] ; ToDo: I couldn't use pbir/single-attribute-resolver here. {0 val, 1 val,...)
           (let [ref? (= :db.type/ref (some #(when (= (:attr/id %) att) (:attr/type %)) attr-types))]
             (make-resolver (symbol (str "iri-to-" (namespace att) "-" (name att)))
                            {:input [:resource/iri],
                             :output [att],
                             :fn (attr-fn att ref?)}))))))

(pco/defresolver resource-by-iri [{:resource/keys [iri]}]
  {:resource/body (dp/pull *conn* '[*] [:resource/iri iri])})

(defn owl-order
  "Return a comparator value for ordering the keys of a sorted map of owl-db-tools stuff."
  [x y]
  (cond (= x :resource/iri) -1
        (= y :resource/iri)  1
        (= x :resource/name)  -1
        (= y :resource/name)   1
        (= x :resource/namespace) -1
        (= y :resource/namespace)  1
        :else (compare x y))) 

(defn pull-resource
  "Return the nicely sorted sorted-map of a resource; it's like pretty printing."
  [resource-iri conn & {:keys [keep-db-ids? sort?] :or {sort? true}}]
  (binding [*conn* conn]
    (as->  (resource-by-iri {:resource/iri resource-iri}) ?x
        (:resource/body ?x)
        (util/resolve-obj ?x *conn* :keep-db-ids? keep-db-ids?)
        (if sort? (into (sorted-map-by owl-order) ?x) ?x))))

;;; ToDo:
;;;   This one just provides (as a vector) of resource names for the whole DB, or with parameters, some subset of those.
;;;   I need something that would allow such things AND ALSO allow further EQL navigation. This doesn't do that.
;;;   Also, constantly-fn-resolvers don't cache. 
;;; (owl-db '[(:owl/db {:filter-by {:attr :resource/namespace :val "dol"}})])
(def iris-with-filters
  (pbir/constantly-fn-resolver
   :ontology/context
   (fn [env]
     (let  [params (:filter-by (pco/params env)) ; Retrieve parameter, atom or vector.
            filters (if (vector? params) params (vector params))]
       (-> (d/q `[:find [?iri ...]
                  :where
                  [?e :resource/iri ?iri]
                  ~@(map (fn [p] `[?e ~(:attr p) ~(:val p)]) filters)] *conn*)
           sort
           vec)))))

(def diag (atom nil))

(pco/defresolver onto-test [{:ontology/keys [context]}]
  {:ontology/object-property
   (let [res (filterv (fn [iri]
                        (d/q `[:find ?e . 
                               :where
                               [?e :resource/iri ~iri]
                               [?e :rdf/type ?type]
                               [?type :resource/iri :owl/ObjectProperty]] *conn*))
                      context)]
     (println "Here!")
     (reset! diag {:context context :res res})
     res)})

(def other-resolvers "a vector of miscellaneous resolvers defined in this namespace"
  [;resource-by-iri
   onto-test
   iris-with-filters])

(defn register-resolvers!
  "Register resolvers and create a boundary interface function that takes as an argument 
   EQL compatible with any of the resolvers defined/computed in this file."
  [conn]
   (-> (make-resource-attr-resolvers conn)
       (into other-resolvers)
       pci/register
       p.eql/boundary-interface))

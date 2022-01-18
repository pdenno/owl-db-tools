(ns pdenno.owl-db-tools.resolvers
  "Pathom3 resolvers to make accessing the DB easy."
  (:require
   [com.wsscode.misc.macros    :as pmacs]
   [com.wsscode.pathom3.connect.indexes :as pci]
   [com.wsscode.pathom3.connect.operation :as pco]
   [datahike.api               :as d]
   [datahike.pull-api          :as dp]
   [pdenno.owl-db-tools.core   :as owl :refer [*conn*]]
   [pdenno.owl-db-tools.util   :as util]))

(def attr-info "ToDo: will probably have to be a function."
  (d/q '[:find  ?id ?type
         :keys attr/id attr/type
         :where [?e :db/ident ?id] [?e :db/valueType ?type]]
       *conn*))

(defn attr-types
  "Return map of two sets (with the following two keys):
    - :resource? :  DB attributes that appear directly in resources.
    - :experssion? : DB attributes that do not appear directly in resources."
  [conn]
  (->> (d/q '[:find [?attr ...] :where [_ ?attr _]] conn)
       (remove (fn [x] (#{"source" "app" "box" "db"} (namespace x))))
       (reduce (fn [res attr]
                 (if (d/q `[:find ?x . :where [?x ~attr] [?x :resource/id]] *conn*)
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
    (fn [_env {:resource/keys [rdf-uri]}]
      (when *conn*
         (-> (dp/pull *conn* [attr] [:resource/id rdf-uri])
             (util/resolve-obj *conn*))))
    (fn [_env {:resource/keys [rdf-uri]}]
      (when *conn*
        (dp/pull *conn* [attr] [:resource/id rdf-uri])))))

(defn make-resource-attr-resolvers
  "Return a vector of resolvers for rdf resource attributes."
  [conn]
  (let [attrs (-> (attr-types conn) :resource? vec sort)] ; sort for easier debugging.
    (vec (for [att attrs]
           (let [ref? (= :db.type/ref (some #(when (= (:attr/id %) att) (:attr/type %)) attr-info))]
             (make-resolver (symbol (str "rdf-uri-to-" (namespace att) "-" (name att)))
                            {:input [:resource/rdf-uri],
                             :output [att],
                             :fn (attr-fn att ref?)}))))))

(pco/defresolver resource-by-uri [{:resource/keys [rdf-uri]}]
  {:resource/body (dp/pull *conn* '[*] [:resource/id rdf-uri])})

(defn pull-resource
  "Return the nicely sorted sorted-map of a resource; it's like pretty printing."
  [resource-id conn & {:keys [keep-db-ids? sort?] :or {sort? true}}]
  (binding [*conn* conn]
    (cond->   (resource-by-uri {:resource/rdf-uri resource-id})
        true  :resource/body
        true  (util/resolve-obj *conn* :keep-db-ids? keep-db-ids?)
        sort? identity))) ; ToDo: Sort

(def other-resolvers "a vector of pre-defined resolvers for accessing the OWL DB." [resource-by-uri])

(defn register-resolvers!
  "Alter the value of the var index to refer to the automatically-generated
   Pathom resovlers, plus others defined in this namespace. (See the var other-resolvers)."
  [conn]
  (pci/register
   (into other-resolvers
         (make-resource-attr-resolvers conn))))

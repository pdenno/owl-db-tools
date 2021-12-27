(ns pdenno.owl-db-tools.core
  "Load the datahike database from JENA content; define pathom resolvers."
  (:require
   [cheshire.core]
   [clojure.set              :as set]
   [datahike.api             :as d]
   [datahike.pull-api        :as dp]
   [edu.ucdenver.ccp.kr.kb   :as kb]
   [edu.ucdenver.ccp.kr.jena.kb]
   [edu.ucdenver.ccp.kr.rdf :as rdf]
   [edu.ucdenver.ccp.kr.sparql :as sparql]
   [taoensso.timbre    :as log])
  (:import java.io.ByteArrayInputStream))

;;; I use 'dvecs' to mean the JENA data stored as a vector of vectors,
;;; I use 'dmaps' to mean the same data in maps. 

;;; ToDo:
;;;   - I don't think there is any reason to have :temp/ resources. I've only eliminated :rdf/List so far.
;;;   - If 'learned-schema' is a throwaway, can I put somewhere else?
;;;   - Define a logger appender (if decided to keep the logger).

(defonce conn nil) ; "The connection to the database"
(def diag (atom nil))

(def default-prefixes
   {"daml"  {:uri "http://www.daml.org/2001/03/daml+oil"       :ref-only? true},
    "dc"    {:uri "http://purl.org/dc/elements/1.1/"           :ref-only? true},
    "owl"   {:uri "http://www.w3.org/2002/07/owl"              :ref-only? true},
    "rdf"   {:uri "http://www.w3.org/1999/02/22-rdf-syntax-ns" :ref-only? true},
    "rdfs"  {:uri "http://www.w3.org/2000/01/rdf-schema"       :ref-only? true},
    "xml"   {:uri "http://www.w3.org/XML/1998/namespace"       :ref-only? true},
    "xsd"   {:uri "http://www.w3.org/2001/XMLSchema"           :ref-only? true}})

(def multi-valued-property?
  "Properties where the object can have many values
   Many-valued properties aren't the same as things bearing temp values. For example, :owl/complement of can have a temp."
  #{:owl/allValuesFrom :owl/disjointUnionOf :owl/equivalentClasses :owl/equivalentProperty :owl/hasKey :owl/intersectionOf :owl/members
    :owl/onProperties :owl/oneOf :owl/propertyChainAxiom :owl/sameAs :owl/someValuesFrom  :owl/unionOf :owl/withRestrictions
    :rdfs/comment :rdfs/domain :rdfs/range :rdfs/subClassOf}) ; POD I'm guessing on :rdfs/comment!

(def single-valued-property?
  "The following are properties that can only take one value."
  #{:owl/backwardCompatibleWith :owl/cardinality :owl/complementOf :owl/disjointWith :owl/equivalentClass :owl/hasValue
    :owl/imports :owl/inverseOf :owl/minCardinality :owl/onProperty :owl/versionInfo
    :rdf/first :rdf/rest :rdf/type :rdfs/label :rdfs/subPropertyOf})

;;; POD Temporary
(def temporary-to-be-learned-property?
  #{:edns/defined-by :edns/defines :edns/specialized-by :edns/specializes :mod/clojureCodeNote})

(def not-stored-property?
  "These aren't stored; they are used to create a vector."
  #{:rdf/first :rdf/rest})

;;; The idea here is that a resource (a keyword in a namespace signifying what ontology it is defined in) is 
;;; a unique key to an entity. There is a bijection between :resource/id and :db/id
;;; The types of things are defined in https://www.w3.org/TR/2012/REC-owl2-quick-reference-20121211/
(def owl-schema
  [#:db{:ident :resource/id  :cardinality :db.cardinality/one :valueType :db.type/keyword :unique :db.unique/identity}
   #:db{:ident :resource/ref :cardinality :db.cardinality/one :valueType :db.type/keyword}
   
   ;; multi-valued properties
   #:db{:ident :owl/allValuesFrom      :cardinality :db.cardinality/many :valueType :db.type/ref}
   #:db{:ident :owl/disjointUnionOf    :cardinality :db.cardinality/many :valueType :db.type/ref}
   #:db{:ident :owl/equivalentClasses  :cardinality :db.cardinality/many :valueType :db.type/ref}
   #:db{:ident :owl/equivalentProperty :cardinality :db.cardinality/many :valueType :db.type/ref}
   #:db{:ident :owl/hasKey             :cardinality :db.cardinality/many :valueType :db.type/ref}
   #:db{:ident :owl/intersectionOf     :cardinality :db.cardinality/many :valueType :db.type/ref}
   #:db{:ident :owl/members            :cardinality :db.cardinality/many :valueType :db.type/ref}
   #:db{:ident :owl/onProperties       :cardinality :db.cardinality/many :valueType :db.type/ref}
   #:db{:ident :owl/oneOf              :cardinality :db.cardinality/many :valueType :db.type/ref}
   #:db{:ident :owl/propertyChainAxiom :cardinality :db.cardinality/many :valueType :db.type/ref}
   #:db{:ident :owl/sameAs             :cardinality :db.cardinality/many :valueType :db.type/ref}
   #:db{:ident :owl/someValuesFrom     :cardinality :db.cardinality/many :valueType :db.type/ref}
   #:db{:ident :owl/unionOf            :cardinality :db.cardinality/many :valueType :db.type/ref}
   #:db{:ident :owl/withRestrictions   :cardinality :db.cardinality/many :valueType :db.type/ref}

   ;; single-valued properties
   #:db{:ident :owl/backwardCompatibleWith :cardinality :db.cardinality/one :valueType :db.type/string}
   #:db{:ident :owl/cardinality            :cardinality :db.cardinality/one :valueType :db.type/number} ; was long
   #:db{:ident :owl/complementOf           :cardinality :db.cardinality/one :valueType :db.type/ref}
   #:db{:ident :owl/disjointWith           :cardinality :db.cardinality/one :valueType :db.type/ref}
   #:db{:ident :owl/equivalentClass        :cardinality :db.cardinality/one :valueType :db.type/ref}
   #:db{:ident :owl/hasValue               :cardinality :db.cardinality/one :valueType :db.type/boolean}
   #:db{:ident :owl/imports                :cardinality :db.cardinality/one :valueType :db.type/ref}
   #:db{:ident :owl/inverseOf              :cardinality :db.cardinality/one :valueType :db.type/ref}
   #:db{:ident :owl/minCardinality         :cardinality :db.cardinality/one :valueType :db.type/number}
   #:db{:ident :owl/onProperty             :cardinality :db.cardinality/one :valueType :db.type/ref}
   #:db{:ident :owl/versionInfo            :cardinality :db.cardinality/one :valueType :db.type/string}

   #:db{:ident :owl/string-val     :cardinality :db.cardinality/one :valueType :db.type/string}
   #:db{:ident :owl/keyword-val    :cardinality :db.cardinality/one :valueType :db.type/keyword}
   #:db{:ident :owl/number-val     :cardinality :db.cardinality/one :valueType :db.type/number}
   #:db{:ident :owl/boolean-val    :cardinality :db.cardinality/one :valueType :db.type/boolean}])   

(def rdfs-schema
  [#:db{:ident :rdfs/domain        :cardinality :db.cardinality/many :valueType :db.type/ref}
   #:db{:ident :rdfs/range         :cardinality :db.cardinality/many :valueType :db.type/ref}
   #:db{:ident :rdfs/comment       :cardinality :db.cardinality/many :valueType :db.type/string}
   #:db{:ident :rdfs/label         :cardinality :db.cardinality/many :valueType :db.type/string}
   #:db{:ident :rdfs/subClassOf    :cardinality :db.cardinality/many :valueType :db.type/ref}
   
   #:db{:ident :rdfs/label         :cardinality :db.cardinality/one  :valueType :db.type/string}
   #:db{:ident :rdfs/subPropertyOf :cardinality :db.cardinality/one  :valueType :db.type/ref}])

(def rdf-schema
  [#:db{:ident :rdf/type :cardinality :db.cardinality/one :valueType :db.type/ref}])

(def full-schema (atom (into (into rdf-schema rdfs-schema) owl-schema)))

(def boxed-property? "These can take multiple values of various types, some need to be boxed." #{:owl/oneOf})

(def loaded-ok? "When false it is usually because of a timeout slurping a URL." (atom true))

(defn load-local
  "Load an ontology stored in a file in resources."
  [src]
  (-> src :access slurp .getBytes ByteArrayInputStream.))

(defn load-remote
  "Experience suggests ontologydesignpatterns.org cannot be relied upon.
   Return nil if you can't load from the :access URL, otherwise return the stream."
  [src & {:keys [timeout] :or {timeout 10000}}]
  (let [p (promise)]
    (future (deliver p (-> src :uri slurp .getBytes ByteArrayInputStream.)))
    (if-let [result (deref p timeout nil)]
      result
      (do
        (reset! loaded-ok? false)
        (log/error "Timeout: Failed to access" (:uri src))))))

(def long2short "This is used to replace long URI in resources with prefixes." (atom nil))
(def names-an-onto? (atom nil))

(defn set-long2short!
  "Initialize an atom to a map of URIs to short names."
  [ontos]
  (->> ontos
      (reduce-kv (fn [m k v] (assoc m k (:uri v))) {})
      set/map-invert
      (reset! long2short)))

(defn set-onto-atoms!
  "Initialize an atom to a set of strings naming ontologies used, names-an-onto?."
  [ontos]
  (set-long2short! ontos)
  (->> @long2short
       keys
       (map #(let [cnt (count %)] (if (= \# (nth % (dec cnt))) (subs % 0 (dec cnt)) %)))
       set
       (reset! names-an-onto?)))
                      
(defn load-jena
  "Using JENA, return an rdf/KB object with the argument ontologies loaded."
  [{:keys [uri access format] :as src}]
  (log/info "(Re)loading ontologies.")
  (let [kb (kb/kb :jena-mem)]
    (when @loaded-ok?
      (log/info "Loading" uri))
    (when-let [stream (if access (load-local src) (load-remote src))]
      (rdf/load-rdf kb stream (or format :rdfxml)))
    ;; If you do the sync, some resources won't print namespace-qualified in sparql queries.
    ;; It will be correct in the JENA DB, just not printed. More on this (possibly related!):
    ;;    (1) https://github.com/drlivingston/kr
    ;;    (2) https://jena.apache.org/tutorials/rdf_api.html#ch-Prefixes
    #_(rdf/synch-ns-mappings kb)
    kb))

(defn keywordize-triple
  "From Jena, all the resources come back as symbols in either namespace '_' or 'http:'.
   These are to be rendered as keywords in the namespace 'temp' for _, and the short prefix for the full URL otherwise.
   The function returns a vector of the converted [x y z] triple."
  [{:?/syms [x y z] :as data} & {:keys [l2s] :or {l2s @long2short}}]
  (letfn [(convert [v & {:keys [ref?]}]
            (if (symbol? v)
              (cond
                (= (namespace v) "_") (keyword "temp" (str "t" (name v))), ; name can't begin with a number.
                (= (namespace v) "http:")
                (let [[success base nam] (re-matches #"^(http://[^#]*)#?(.*)$" (str v))]
                  (cond (and success (not-empty nam))
                        (if-let [prefix (get l2s base)]
                          (if ref? {:resource/ref (keyword prefix nam)} (keyword prefix nam))
                          (if ref? {:resource/ref (keyword base nam)}   (keyword base nam))),
                        (and (empty? nam) (@names-an-onto? base)) ; not found
                        (keyword "$onto" base), 
                        :else ; ToDo: is this one even possible?
                        (do (log/warn "(2) Don't know what to do with" (str v))
                            (reset! diag data)
                            (keyword "BUG" (str v))))))
              v))]
    (vector (convert x) (convert y) (convert z :ref? true))))

;;;---- Operating on the keywordized dvecs -----------------------------------------------
(defn temp-id? [k]
  (and (keyword? k)
       (= "temp" (namespace k))))

(defn list-starters
  "Return a list of triples that start lists. They have a :rdf/first, 
   but they aren't used as the :rdf/rest of anything."
  [dvecs]
  (->> dvecs
       (filter #(= :rdf/first (nth % 1)))
       (remove (fn [starter?]
                 (let [resource (first starter?)]
                   (some #(and (= resource (nth % 2))
                               (= :rdf/rest (nth % 1)))
                         dvecs))))))

(defn resolve-rdf-lists
  "Return a map of toplevel rdf/Lists."
  [dvecs]
  (let [list-stuff (filter #(#{:rdf/first :rdf/rest} (second %)) dvecs)
        starters (list-starters list-stuff)]
    (reduce (fn [m starter-trip]
              (assoc m
                     (first starter-trip)
                     (loop [lis []
                            resource (first starter-trip)
                            cnt 0]
                       (let [val (some #(when (and (= (nth % 0) resource)
                                                   (= (nth % 1) :rdf/first))
                                          (nth % 2))
                                       list-stuff)
                             rest-trip (some #(when (and (= (nth % 0) resource)
                                                         (= (nth % 1) :rdf/rest))
                                                %)
                                             list-stuff)]
                         (cond (= {:resource/ref :rdf/nil} (nth rest-trip 2)) (conj lis val),
                               (> cnt 100) (throw (ex-info "Didn't find RDF list termination:" {:starter-triple starter-trip})),
                               :else (recur (conj lis val) (nth rest-trip 2) (inc cnt)))))))
            {}
            starters)))

;;; From nb-agent
(defn learn-schema-from-data
  "Return an schema update with what is learned from the AST or examples."
  ([data] (learn-schema-from-data {} data))
  ([schema-map data]
   (cond (vector? data) (reduce (fn [sm v] (learn-schema-from-data sm v)) schema-map data)
         (map?    data) (reduce-kv (fn [sm k v]
                                     (cond (coll? v)
                                           (learn-schema-from-data
                                            (-> sm
                                                (assoc-in [k :db/cardinality] :db.cardinality/many)
                                                ;(assoc-in [k :db/unique]      :db.unique/identity) ; POD guessing.
                                                (assoc-in [k :db/valueType]   :db.type/ref))
                                            v)
                                           (contains? sm k) sm
                                           :else (cond-> sm
                                                   true          (assoc-in [k :db/cardinality] :db.cardinality/one)
                                                   (string?  v)  (assoc-in [k :db/valueType]   :db.type/string)
                                                   (float?   v)  (assoc-in [k :db/valueType]   :db.type/number)
                                                   (integer? v)  (assoc-in [k :db/valueType]   :db.type/number)
                                                   (keyword? v)  (assoc-in [k :db/valueType]   :db.type/keyword)
                                                   (boolean? v)  (assoc-in [k :db/valueType]   :db.type/boolean))))
                                   schema-map
                                   data)
         :else schema-map)))

(defn learn-type
  "Return the :db/valueType for the data."
  [prop examples]
  (let [prop-examples (filter #(= prop (second %)) examples)
        data          (map #(nth % 2) prop-examples)
        types         (->> data (map type) distinct)
        typ           (first types)]
    (if (== 1 (count types)) ; case doesn't work here. 
      (cond (= typ clojure.lang.Keyword) :db.type/keyword, 
            (= typ java.lang.String)     :db.type/string
            (= typ java.lang.Long)       :db.type/number
            (= typ java.lang.Boolean)    :db.type/boolean)
      (log/error "Found multiple types while learning" prop types))))

(defn learn-cardinality
  "Return either :db.cardinality/many or :db.cardinality/one based on evidence."
  [prop examples]
  (let [prop-examples (filter #(= prop (second %)) examples)
        individuals   (->> prop-examples (map first))]
    (if (== (-> individuals distinct count)
            (-> individuals count))
      :db.cardinality/one
      :db.cardinality/many)))

(defn learn-schema!
  "Create a schema from what we know about the owl, rdfs, and rdf parts 
    plus any additional triples created by ontologies."
  [dvecs conn]
  (let [known-property?   (into single-valued-property? multi-valued-property?)
        examples          (remove #(known-property? (nth % 1)) dvecs)
        unknown-properties (->> examples (mapv second) distinct)
        learned (atom [])]
    (doseq [prop unknown-properties]
      (if (some #(= prop (:db/ident %)) @full-schema)
        (log/info "Already known: " prop)
        (let [spec #:db{:ident prop
                        :cardinality (learn-cardinality prop examples)
                        :valueType   (learn-type prop examples)}]
          (swap! learned #(conj % spec))
          (swap! full-schema #(conj % spec)))))
      (binding [log/*config* (assoc log/*config* :min-level :info)]
        (d/transact conn @learned))))
                
;;;--------------------------------- create and operate on the dmaps -----------------------
(defn learned-multi?
  "Return true if the property is learned and :db/cardinality :db.cardinality/many"
  [prop learned-schema]
  (when-let [spec (some #(when (= prop (:db/ident %)) %) learned-schema)]
    (= (:db/cardinality spec) :db.cardinality/many)))

(defn learned-single?
  "Return true if the property is learned and :db/cardinality :db.cardinality/one."
  [prop learned-schema]
  (when-let [spec (some #(when (= prop (:db/ident %)) %) learned-schema)]
    (= (:db/cardinality spec) :db.cardinality/one)))

(defn box-value 
  "Return the value boxed (that is as a map where the key identifies the type.
   This is necessary where an owl property can have multiple types (like :owl/oneOf)."
  [v]
  (letfn [(b-val [x]
            (cond (string? x)  {:owl/string-val x}
                  (keyword? x) {:owl/keyword-val x}
                  (number? x)  {:owl/number-val x}
                  (boolean? x) {:owl/boolean-val x}))]
    (if (coll? v)
      (mapv b-val v)
      (b-val v))))
  
(defn triples2maps
  "Read all the triples and return a map of them where each entry is an RDF resource represented
   as a map where each key is an attribute of the resource."
  [dvecs rdf-lists learned-schema]
  (reduce (fn [m [o a v]]
            (let [v (as-> v ?v
                      (or (get rdf-lists ?v) ?v)
                      (if (boxed-property? a) (box-value ?v) ?v))]
              (cond (not-stored-property?    a) m, 
                    (single-valued-property? a) (assoc-in m [o a] v),
                    (multi-valued-property?  a)        (update-in m [o a] #(if (vector? %2) (into %1 %2) (vec (conj %1 %2))) v), 
                    (learned-multi?  a learned-schema) (update-in m [o a] #(if (vector? %2) (into %1 %2) (vec (conj %1 %2))) v), 
                    (learned-single? a learned-schema) (assoc-in m [o a] v),
                    :else (log/error "What is this property?" a))))
          {}
          dvecs))

(defn partition-temp-perm
  "Create a map with two keys:
     :temp-data - a map of temp resources indexed by their :resource/id.
     :perm-data - a subset of the argument vector with temp maps removed. "
  [dmaps]
  (reduce (fn [res m]
            (let [id (:resource/id m)]
              (if (temp-id? id)
                (assoc-in  res [:temp-data id] (dissoc m :resource/id))
                (update res :perm-data conj m))))
          {:temp-data {} :perm-data []}
          dmaps))

(defn resolve-temp-internal
  "Temp resources can reference other temp resources.
   This resolves everything."
  [temp-maps]
  (let [progress? (atom true)]
    (letfn [(rt-aux [obj tm]
              (cond (temp-id? obj)
                    (if-let [v (get tm obj)]
                      (do (reset! progress? true) v)
                      (log/error "Could not find" obj)),
                    (map?  obj) (reduce-kv (fn [m k v] (assoc m k (rt-aux v tm))) {} obj), 
                    (coll? obj) (mapv #(rt-aux % tm) obj),
                    :else obj))]
      (loop [tmaps temp-maps
             count 0]
        (reset! progress? false)
        (let [new-maps
              (reduce-kv (fn [m k v]
                           (assoc m k (reduce-kv (fn [mm kk vv] (assoc mm kk (rt-aux vv tmaps)))
                                                 {}
                                                 v))) ; v is a map about a temp.
                         {}
                         tmaps)]
          (cond (> count 5) (log/error "Temp data has loops.")
                @progress?  (recur new-maps (inc count))
                :else new-maps))))))
                                             
(defn resolve-temps
  "Replace every temp reference with its value."
  [dmaps]
  (let [{:keys [temp-data perm-data]} (partition-temp-perm dmaps)
        temp-data (resolve-temp-internal temp-data)]
    (letfn [(rt-aux [obj]
              (cond (temp-id? obj)
                    (or (get temp-data obj) (log/error "Could not find" obj)),
                    (map? obj) (reduce-kv (fn [m k v] (assoc m k (rt-aux v))) {} obj),
                    (coll? obj) (mapv rt-aux obj),
                    :else obj))]
      (mapv rt-aux perm-data))))

(defn site-online?
  "Return true if the site reacts within timeout"
  [url timeout]
  (let [p (promise)]
    (future (deliver p (slurp url)))
    (if (string? (deref p timeout false)) true false)))

(def diag-jena (atom nil))

(defn store-onto!
  "Transform RDF triples in argument jena-maps, resolve rdf/List, create maps of resources. 
   Then store it." 
  [conn jena-maps]
  (let [dvecs (mapv #(keywordize-triple % :long2short long2short) jena-maps)]
    (reset! diag dvecs)
    ;(learn-schema! dvecs conn) ; <=================================================================================== Start here
    (let [rdf-lists (resolve-rdf-lists dvecs)
          dmaps (->> (triples2maps dvecs rdf-lists @full-schema)
                     (reduce-kv (fn [res k v] (conj res (assoc v :resource/id k))) [])
                     resolve-temps)
          resources (reduce-kv (fn [res _ v] (if-let [id (:resource/id v)] (conj res id) res)) #{} dmaps)]
      (reset! diag {:dmaps dmaps :resources resources})
      (binding [log/*config* (assoc log/*config* :min-level :info)]
        ;; BUG HERE: :dol/physical-quality is in resources, which are transacted here, but it doesn't
        ;; help the Nothing found for entity id  :dol/physical-quality.
        ;; It looks like commenting out the :db/unique :db.unique/identity doesn't help.
        (d/transact conn (mapv (fn [x] {:resource/id x}) resources))
        (d/transact conn dmaps #_(-> dmaps vals vec))))))

(defn create-db!
  "If rebuild? is true, read OWL with JENA and write it into a Datahike DB. 
   Otherwise, just set the connection atom, conn.
   BTW, if this doesn't get a response within 15 secs from slurping odp.org, it doesn't rebuild the DB."
  [db-cfg ontos & {:keys [check-sites check-sites-timeout rebuild?] :or {check-sites-timeout 15000}}]
  (let [site-ok? (if check-sites (every? #(site-online? % check-sites-timeout) check-sites) true)
        all-ontos  (merge default-prefixes ontos)
        load-ontos (reduce-kv (fn [m k v] (if (:ref-only? v) m (assoc m k v))) {} all-ontos)]
    (set-onto-atoms! all-ontos)
    (cond (and rebuild? site-ok?)
          (binding [log/*config* (assoc log/*config* :min-level :info)]
            (when (d/database-exists? db-cfg) (d/delete-database db-cfg))
            (d/create-database db-cfg)
            (log/info "Created schema DB")
            (alter-var-root (var conn) (fn [_] (d/connect db-cfg)))
            (d/transact conn @full-schema)
            (doseq [onto-spec (vals load-ontos)]
              (let [jena-maps (-> (load-jena onto-spec) (sparql/query '((?/x ?/y ?/z))))]
                (reset! diag-jena jena-maps)
                (store-onto! conn jena-maps)))
            conn),
          (not site-ok?) (log/error "Could not connect to a site needed for ontologies. Not rebuilding DB."),
          (d/database-exists? db-cfg) (do (alter-var-root (var conn) (fn [_] (d/connect db-cfg))) conn),
          :else (log/warn "There is no DB to connect to."))))

(defn db-ref?
  "Return true if to object is a :db/id
   (It looks to me that a datahike ref is a map with exactly one key: :db/id.)"
  [obj]
  (and (map? obj) (= '(:db/id) (keys obj))))

(defn resolve-db-id
  "Return the form resolved, possibly removing some properties."
  ([form conn-atom] (resolve-db-id form conn-atom #{}))
  ([form conn-atom filter-set]
   (letfn [(resolve-aux [obj]
             (cond
               (db-ref? obj) (let [res (dp/pull @conn-atom '[*] (:db/id obj))]
                               (if (= res obj) nil (resolve-aux res)))
               (map? obj) (reduce-kv (fn [m k v] (if (filter-set k) m (assoc m k (resolve-aux v))))
                                     {}
                                     obj)
               (vector? obj)      (mapv resolve-aux obj)
               (set? obj)    (set (mapv resolve-aux obj))
               (coll? obj)        (mapv resolve-aux obj) ; I wonder why these would exist!
               :else  obj))]
     (resolve-aux form))))

(defn pull-resource
  "Return the map of a resource."
  [resource-id conn-atom]
  (when-let [db-id (d/q `[:find ?e . :where [?e :resource/id ~resource-id]] @conn-atom)]
    (resolve-db-id {:db/id db-id} conn-atom)))

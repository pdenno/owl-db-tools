(ns pdenno.owl-db-tools.core
  "Load the datahike (DH) database from Jena content; define pathom resolvers."
  (:require
   [cheshire.core]
   [clojure.string             :as str]
   [datahike.api               :as d]
   [datahike.pull-api          :as dp]
   [edu.ucdenver.ccp.kr.kb     :as kb]      ; ToDo: This version of Jena uses log4j 1.2 which does not have the vulnerability:
   [edu.ucdenver.ccp.kr.jena.kb]            ; https://www.cisa.gov/uscert/apache-log4j-vulnerability-guidance
   [edu.ucdenver.ccp.kr.rdf    :as rdf]     ; However,  some work is needed to avoid the a configuration warning.
   [edu.ucdenver.ccp.kr.sparql :as sparql]  ; See https://logging.apache.org/log4j/1.2/faq.html#a3.5
   [pdenno.owl-db-tools.util   :as util]
   [taoensso.timbre            :as log])
  (:import java.io.ByteArrayInputStream))

;;; * I use 'dvecs' to mean the Jena data stored as a vector of 3-element vectors.
;;; * I use 'dmaps' to mean the dvecs data reorganized as a map indexed by :resource/id or a keyword in the "temp" from Jena.
;;; * I use 'dmapv' to mean the dmaps data reorganized as a vector of maps with temps resolved.
;;; And those three bullets points pretty much describe the program architecture!

;;; ToDo:
;;;   - Add :user-schema to create-db!.
;;;   - Define a log4j configuration for Jena (or fork edu.ucdever.cpp.kr and fix it myself).
;;;   - Making resource on-the-fly: :owl/Class (investigate)
;;;   - Making resource on-the-fly: :owl/Restriction (investigate)

(declare sources)

(def debugging? false)
(util/config-log (if debugging? :debug :info))

;;; There is a bijection between :resource/id and a subset of :db/id.
;;; The types of OWL things are defined in https://www.w3.org/TR/2012/REC-owl2-quick-reference-20121211/
(def app-schema
  [#:db{:ident :resource/id       :cardinality :db.cardinality/one :valueType :db.type/keyword :unique :db.unique/identity}
   #:db{:ident :source/short-name :cardinality :db.cardinality/one :valueType :db.type/string  :unique :db.unique/identity}
   #:db{:ident :source/long-name  :cardinality :db.cardinality/one :valueType :db.type/string  :unique :db.unique/identity}
   #:db{:ident :source/loaded?    :cardinality :db.cardinality/one :valueType :db.type/boolean}
   #:db{:ident :box/boolean-val   :cardinality :db.cardinality/one :valueType :db.type/ref}   ; These for useful when
   #:db{:ident :box/keyword-val   :cardinality :db.cardinality/one :valueType :db.type/ref}   ; for example, boxing is necessary,
   #:db{:ident :box/number-val    :cardinality :db.cardinality/one :valueType :db.type/ref}   ; such as when you need to store a
   #:db{:ident :box/string-val    :cardinality :db.cardinality/one :valueType :db.type/ref}]) ; ref, but have one of these db.type.

(def owl-schema
   ;; multi-valued properties
  [#:db{:ident :owl/allValuesFrom      :cardinality :db.cardinality/many :valueType :db.type/ref}
   #:db{:ident :owl/disjointUnionOf    :cardinality :db.cardinality/many :valueType :db.type/ref}
   #:db{:ident :owl/disjointWith       :cardinality :db.cardinality/many :valueType :db.type/ref}
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
   #:db{:ident :owl/cardinality            :cardinality :db.cardinality/one :valueType :db.type/number}
   #:db{:ident :owl/complementOf           :cardinality :db.cardinality/one :valueType :db.type/ref}
   #:db{:ident :owl/equivalentClass        :cardinality :db.cardinality/one :valueType :db.type/ref}
   #:db{:ident :owl/hasValue               :cardinality :db.cardinality/one :valueType :db.type/boolean}
   #:db{:ident :owl/imports                :cardinality :db.cardinality/one :valueType :db.type/ref}
   #:db{:ident :owl/inverseOf              :cardinality :db.cardinality/one :valueType :db.type/ref}
   #:db{:ident :owl/minCardinality         :cardinality :db.cardinality/one :valueType :db.type/number}
   #:db{:ident :owl/onProperty             :cardinality :db.cardinality/one :valueType :db.type/ref}
   #:db{:ident :owl/versionInfo            :cardinality :db.cardinality/one :valueType :db.type/string}])

(def rdfs-schema
  [#:db{:ident :rdfs/domain        :cardinality :db.cardinality/many :valueType :db.type/ref}
   #:db{:ident :rdfs/range         :cardinality :db.cardinality/many :valueType :db.type/ref}
   #:db{:ident :rdfs/comment       :cardinality :db.cardinality/many :valueType :db.type/string}
   #:db{:ident :rdfs/label         :cardinality :db.cardinality/many :valueType :db.type/string}
   #:db{:ident :rdfs/subClassOf    :cardinality :db.cardinality/many :valueType :db.type/ref}

   #:db{:ident :rdfs/label         :cardinality :db.cardinality/one  :valueType :db.type/string}
   #:db{:ident :rdfs/subPropertyOf :cardinality :db.cardinality/one  :valueType :db.type/ref}])

(def rdf-schema
  [#:db{:ident :rdf/type      :cardinality :db.cardinality/one :valueType :db.type/ref} ; boxed because not always a keyword.
   #:db{:ident :rdf/parseType :cardinality :db.cardinality/one :valueType :db.type/keyword}]) ; e.g. :collection

(def static-schema "The subset of schema define above."  (atom nil))
(def full-schema "The currently schema, consisting of static-schema plus what is learned while loading." (atom nil))

(def multi-valued-property?
  "Properties where the object can have many values
   Many-valued properties aren't the same as things bearing temp values. For example, :owl/complement of can have a temp."
  (atom nil))

(def single-valued-property?
  "The following are properties that can only take one value."
  (atom nil))

(defn reset-for-new-db! []
  (reset! static-schema (reduce into [] [owl-schema app-schema rdfs-schema rdf-schema]))
  (reset! full-schema @static-schema)
  (reset! single-valued-property?
          (->> full-schema deref (filter #(= :db.cardinality/one  (:db/cardinality %))) (map :db/ident) set))
  (reset! multi-valued-property?
          (->> full-schema deref (filter #(= :db.cardinality/many (:db/cardinality %))) (map :db/ident) set)))

(reset-for-new-db!) ; ToDo: Really needed here?

(def not-stored-property?
  "These aren't stored; they are used to create vectors."
  #{:rdf/first :rdf/rest})

(def real-keyword? ; ToDo shouldn't this reflect learning?
  "Other keywords are assumed to be resources"
  (->>
   @full-schema
   (filter #(and (= (:db/valueType %) :db.type/keyword) (not= (:db/ident %) :resource/id)))
   (map :db/ident)
   set))

(def boxed-property? "These can take multiple values of various types, some need to be boxed." #{:owl/oneOf :rdf/type})

(def loaded-ok? "When false it is usually because of a timeout slurping a URL." (atom true))

(defn valid-for-transact?
  [data]
  (cond (nil? data) (throw (ex-info "nil is not valid for DH transact." {:nil data})),
        (and (map? data) (empty? data)) (throw (ex-info "{} is not valid for DH transact." {:empty data})),
        (map? data) (reduce-kv (fn [m k v] (assoc m k (valid-for-transact? v))) {} data)
        (vector? data) (mapv valid-for-transact? data)
        (keyword? data) data,
        (string? data) data,
        (number? data) data,
        (boolean? data) data,
        :else (throw (ex-info "unknown datatype" {:unknown data}))))

(defn transact?
  "Optionally check that the data is valid before sending it.
   Does not compare against the schema. Currenlty only checks for nils."
  [conn data & {:keys [check-data?] :or {check-data? debugging?}}]
  (try (when check-data? (valid-for-transact? data))
       (d/transact conn data)
       (catch Exception e (throw (ex-info "Invalid data for d/transact" {:data data :e (.getMessage e)})))))

(defn load-inline
  "When (:access src) is the keyword :inline, then src will also include :inline-data"
  [src]
  (-> src :inline-data .getBytes ByteArrayInputStream.))

(defn load-local
  "Load an ontology stored in a file in resources."
  [src]
  (-> src :access slurp .getBytes ByteArrayInputStream.))

(defn load-remote
  "Experience suggests some sites (e.g. ontologydesignpatterns.org) cannot be relied upon.
   Return nil if you can't load from the :access URL, otherwise return the stream."
  [src & {:keys [timeout] :or {timeout 10000}}]
  (let [p (promise)]
    (future (deliver p (-> src :uri slurp .getBytes ByteArrayInputStream.)))
    (if-let [result (deref p timeout nil)]
      result
      (do
        (reset! loaded-ok? false)
        (log/error "Timeout: Failed to access" (:uri src))))))

(defn load-jena
  "Using Jena, return an rdf/KB object with the argument ontologies loaded."
  [{:keys [uri access format] :as src}]
  (let [kb (kb/kb :jena-mem)]
    (when @loaded-ok?
      (log/info "Loading" uri))
    (when-let [stream (cond (string? access)   (load-local  src),
                            (= access :inline) (load-inline src),
                            :else              (load-remote src))]
      (rdf/load-rdf kb stream (or format :rdfxml)))
    ;; If you do the sync, some resources won't print namespace-qualified in sparql queries.
    ;; It will be correct in the Jena DB, just not printed. More on this (possibly related!):
    ;;    (1) https://github.com/drlivingston/kr
    ;;    (2) https://jena.apache.org/tutorials/rdf_api.html#ch-Prefixes
    #_(rdf/synch-ns-mappings kb)
    kb))

(def long2short "a map from URI strings to prefix strings" (atom nil))

(defn update-long2short!
  "Return a map from uri strings to prefix strings"
  [conn]
  (reset! long2short
          (reduce (fn [res [l s]] (assoc res l s))
                  {}
                  (d/q '[:find ?l ?s :where
                         [?e :source/short-name ?s]
                         [?e :source/long-name  ?l]]
                       @conn))))

(defn onto-keyword
  "Return the keyword representing an ontology or schema (e.g. RDF)."
  [sym]
  (let [[success base nam] (re-matches #"^(http://[^#]*)#?(.*)$" (str sym))]
    (when (and success (empty? nam))
      (keyword "$source" (str/replace base "/" "%")))))

(defn keywordize-triple
  "From Jena, all the resources come back as symbols in either namespace '_' or 'http:'.
   These are to be rendered as keywords in the namespace 'temp' for _, and the short prefix for the full URL otherwise.
   The function returns a vector of the converted [x y z] triple."
  [{:?/syms [x y z]}]
  (letfn [(convert [v & {:keys [ref?]}] ; ToDo: Performance hit for letfn?
            (if (symbol? v)
              (cond
                (= (namespace v) "_") (keyword "temp" (str "t" (name v))), ; Don't let name begin with a number.
                (= (namespace v) "http:")
                (let [[success base nam] (re-matches #"^(http://[^#]*)#?(.*)$" (str v))]
                  (if (and success (not-empty nam))
                    (if-let [prefix (get @long2short base)]
                      (let [res (keyword prefix nam)]
                        (if (and ref? (not= res :rdf/nil)) {:resource/temp-ref res} res))
                      (keyword base nam)),
                    (if-let [onto-kw (onto-keyword v)]
                      (if ref? {:resource/temp-ref (onto-keyword v)} onto-kw)
                      (do (log/warn "Keywordizing triple: ambiguous:" (str v))
                          (keyword "BUG" (str v)))))))
              v))]
    (let [cx (convert x)
          cy (convert y)
          cz (if (real-keyword? cy) (convert z) (convert z :ref? true))]
      ;; ToDo: Write spec for triple.
      (vector cx cy cz))))

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
                         (cond (= :rdf/nil (nth rest-trip 2)) (conj lis val),
                               (> cnt 100) (throw (ex-info "Didn't find RDF list termination:" {:starter-triple starter-trip})),
                               :else (recur (conj lis val) (nth rest-trip 2) (inc cnt)))))))
            {}
            starters)))

(defn learn-type
  "Return the :db/valueType for the data."
  [prop examples]
  (let [prop-examples (filter #(= prop (second %)) examples)
        data          (map #(nth % 2) prop-examples)
        types         (->> data (map type) distinct)
        typ           (first types)]
    (if (== 1 (count types))
      (cond (= typ clojure.lang.Keyword) :db.type/keyword,
            (= typ java.lang.String)     :db.type/string,
            (= typ java.lang.Long)       :db.type/number,
            (= typ java.lang.Boolean)    :db.type/boolean,
            (and (= typ clojure.lang.PersistentArrayMap)
                 (every? #(contains? % :resource/temp-ref) data)) :db.type/ref,
            :else (throw (ex-info "Cannot learn type for" {:data data})))
      (log/error "Found multiple types while learning" prop types))))

(defn learn-cardinality
  "Return either :db.cardinality/many or :db.cardinality/one based on evidence."
  [prop examples]
  (let [prop-examples (filter #(= prop (second %)) examples)
        individuals   (->> prop-examples (map first))
        result (if (== (-> individuals distinct count)
                       (-> individuals count))
                 :db.cardinality/one
                 :db.cardinality/many)]
    (case result
      :db.cardinality/one  (swap! single-valued-property? conj prop)
      :db.cardinality/many (swap! multi-valued-property?  conj prop))
    result))

(defn learn-schema!
  "Create a schema from what we know about the owl, rdfs, and rdf parts
  plus any additional triples created by ontologies."
  [dvecs conn]
  (let [known-property?   (into @single-valued-property? @multi-valued-property?)
        examples          (remove #(known-property? (nth % 1)) dvecs)
        unknown-properties (->> examples (mapv second) distinct)
        learned (atom [])]
    (doseq [prop unknown-properties]
      (when-not (not-stored-property? prop)
        (when (not-any? #(= prop (:db/ident %)) @full-schema)
          (let [spec #:db{:ident prop
                          :cardinality (learn-cardinality prop examples)
                          :valueType   (learn-type prop examples)}]
            (log/debug "learned:" spec)
            (swap! learned #(conj % spec))
            (swap! full-schema #(conj % spec))))))
    (transact? conn @learned)))

(defn box-value
  "Boxing is the process of converting a value type to the type object.
   Return the value boxed as a map where the key identifies the type.
   This is necessary where an owl property can have multiple types (like :owl/oneOf)."
  [v triple]
  (letfn [(b-val [x]
            (cond (string? x)  {:box/string-val x},
                  (keyword? x) {:box/keyword-val x},
                  (number? x)  {:box/number-val x}, ; ToDo: How is this not a ref?
                  (boolean? x) {:box/boolean-val x},
                  (and (map? x) (contains? x :resource/temp-ref)) x,
                  :else (throw (ex-info "Not boxable:" {:v x :triple triple}))))]
    (if (vector? v) (mapv b-val v) (b-val v))))

;;;--------------------------------- create and operate on the dmaps -----------------------
(defn triples2maps
  "Iterate through the triples returning a map keyed by the RDF resource (keyword) represented."
  [dvecs rdf-lists]
  (reduce (fn [m [o a v :as triple]]
            (let [v (as-> v ?v
                      (or (get rdf-lists ?v) ?v)
                      (if (boxed-property? a) (box-value ?v [o a v]) ?v))]
              (when (some nil? triple) (throw (ex-info "Null value:" triple)))
              ;; ToDo: Is this really necessary? Could send vectors?
              (cond (not-stored-property?    a) m,
                    (@single-valued-property? a) (assoc-in m [o a] v),
                    (@multi-valued-property?  a) (update-in m [o a] #(if (vector? %2) (into %1 %2) (vec (conj %1 %2))) v),
                    :else (throw (ex-info "Unknown attribute:" {:attr a})))))
          {}
          dvecs))

(defn partition-temp-perm
  "Create a map with two keys:
     :temp-data - a map of temp resources indexed by their :resource/id.
     :perm-data - a subset of the argument vector with temp maps removed. "
  [dmapv]
  (reduce (fn [res m]
            (let [id (:resource/id m)]
              (if (temp-id? id)
                (assoc-in  res [:temp-data id] (dissoc m :resource/id))
                (update res :perm-data conj m))))
          {:temp-data {} :perm-data []}
          dmapv))

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
          (cond (> count 15) (throw (ex-info "Temp data has loops." {:temp-maps temp-maps})),
                @progress?  (recur new-maps (inc count))
                :else new-maps))))))

(defn resolve-temps
  "Replace every temp reference with its value. Argument is a vector of maps."
  [dmapv]
  (let [{:keys [temp-data perm-data]} (partition-temp-perm dmapv)
        temp-data (resolve-temp-internal temp-data)]
    (letfn [(rt-aux [obj]
              (cond (temp-id? obj)
                    (or (get temp-data obj) (throw (ex-info  "Could not find obj" {:obj obj}))),
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

(defn lookup-resource
  "Return the :db/id of the resource given the its ns-qualified keyword identifier."
  [id conn]
  (or (d/q `[:find ?e . :where [?e :resource/id ~id]] @conn)
      (do (log/debug "Making resource on-the-fly:" id)
          (transact? conn [{:resource/id id}])
          (lookup-resource id conn))))

(defn resolve-temp-refs
  "resolve-temps created references to resources, :resource/temp-ref.
   Stubs for all :resource/ids were transacted to the DB.
   This recursively replaces :resource/temp-ref with their DH entity ID.
   Argument is a vector of maps, one for each resource."
  [dmapv conn]
  (let [save-obj (atom nil)]
    (letfn [(rtr-aux [x]
              (cond (map? x) (if (contains? x :resource/temp-ref) ; then the map has only this key; replace it.
                               (lookup-resource (:resource/temp-ref x) conn)
                               (reduce-kv (fn [m k v]
                                            (if (nil? v)
                                              (throw (ex-info "Failed to resolve temps:" {:obj x :k k :m m}))
                                              (assoc m k (rtr-aux v))))
                                          {} x)),
                    (vector? x) (mapv rtr-aux x),
                    (nil? x) (throw (ex-info "Failed to resolve temps:" {:save-obj @save-obj}))
                    :else x))]
      ;; We do it this way to catch errors at finer granularity.
      (mapv #(do (reset! save-obj %) (rtr-aux %)) dmapv))))

(defn store-onto!
  "Transform RDF triples in argument jena-maps, resolve rdf/List, create maps of resources.
   Then store it."
  [conn jena-maps]
  (let [dvecs (mapv keywordize-triple jena-maps)]
    (learn-schema! dvecs conn)
    (let [rdf-lists (resolve-rdf-lists dvecs)
          dmapv (->> (triples2maps dvecs rdf-lists) ; returns a map indexed by resource or Jena ID in "temp" ns.
                     (reduce-kv (fn [res k v] (conj res (assoc v :resource/id k))) []) ; give them all a :resource/id.
                     resolve-temps) ; gets and returns a vec of maps.
          resources (reduce-kv (fn [res _ v] (if-let [id (:resource/id v)] (conj res id) res)) #{} dmapv)]
      (transact? conn (mapv (fn [x] {:resource/id x}) resources)) ; transact resource stubs.
      (transact? conn (resolve-temp-refs dmapv conn)))))

(defn prefix-maps
  "Define a map relating prefixes to URIs found by means of the Jena.
   Check whether the DB already has a short-name for that resource (user might have specified it).
   If a short-name already exists, keep it. (Both long and short are db.unique db.identity,
   intentionally, so you really can't change it anyway."
  [jkb conn]
  (let [jena-names (as-> (kb/.root-ns-map jkb) ?x
                     (dissoc ?x "")
                     (reduce-kv (fn [res sname lname]
                                  (conj res {:resource/id (onto-keyword lname)
                                             :source/short-name sname
                                             :source/long-name (-> (re-matches #"^([^#]+)#?" lname) second)}))
                                []
                                ?x))
        defined-names (sources conn :l2s true)
        result (atom [])]
    (doseq [name-map jena-names]
      (let [lname (:source/long-name name-map)
            sname (:source/short-name name-map)
            defined-sname (get defined-names lname)]
        (if (and (contains? defined-names lname) (not= sname defined-sname))
          (log/warn "Source uses" sname "as prefix for" lname "but" defined-sname "is already used for that.")
          (swap! result conj name-map))))
    @result))

(defn arg-prefix-maps
  "Define a map relating prefixes to URIs from, the create-db ontos argument."
  [ontos]
  (reduce-kv (fn [res k v]
               (conj res {:resource/id (-> v :uri onto-keyword)
                          :source/short-name k
                          :source/long-name (:uri v)}))
               []
               ontos))

(defn mark-as-stored
  "Add :source/loaded? true to the onto spec"
  [onto-spec conn]
  (let [eid (d/q `[:find ?e . :where [?e :source/long-name ~(:uri onto-spec)]] @conn)]
    (d/transact conn [[:db/add eid :source/loaded? true]])))

;;;------------------------ API functions ---------------------------------
(defn create-db!
  "If rebuild? is true, read OWL with Jena and write it into a Datahike DB.
   Otherwise, just set the connection atom, conn.
   BTW, if this doesn't get a response within 15 secs from slurping odp.org, it doesn't rebuild the DB."
  [db-cfg ontos & {:keys [check-sites check-sites-timeout rebuild?] :or {check-sites-timeout 15000}}]
  (reset-for-new-db!)
  (let [site-ok? (if check-sites (every? #(site-online? % check-sites-timeout) check-sites) true)
        load-ontos (reduce-kv (fn [m k v] (if (:ref-only? v) m (assoc m k v))) {} ontos)]
    (cond (and rebuild? site-ok?)
          (do (when (d/database-exists? db-cfg) (d/delete-database db-cfg))
              (d/create-database db-cfg)
              (let [conn (d/connect db-cfg)]
                (log/info "Created schema DB")
                (transact? conn @full-schema)
                (transact? conn (arg-prefix-maps ontos)) ; URI to prefix maps for argument ontologies
                (doseq [onto-spec (vals load-ontos)]
                  (let [jkb (load-jena onto-spec)
                        jena-maps (sparql/query jkb '((?/x ?/y ?/z)))]
                    (transact? conn (prefix-maps jkb conn))  ; URI to prefix maps from Jena loading the source
                    (update-long2short! conn) ; This is for speed in keywordize-triple.
                    (store-onto! conn jena-maps)
                    (mark-as-stored onto-spec conn)))
                conn)),
          (not site-ok?) (log/error "Could not connect to a site needed for ontologies. Not rebuilding DB."),
          (d/database-exists? db-cfg) (d/connect db-cfg)
          :else (log/warn "There is no DB to connect to."))))

(defn pull-resource
  "Return the map of a resource."
  [resource-id conn & {:keys [keep-db-ids?]}]
  (when-let [obj (binding [log/*config* (assoc log/*config* :min-level :fatal)] ; ToDo macro pattern for pull.
                   (try (dp/pull conn '[*] [:resource/id resource-id])
                        (catch Exception _e nil)))]
    (letfn [(subobj [x]
              (cond (and (map? x) (contains? x :resource/id)) (:resource/id x),         ; It is a whole resource, return ref.
                    (and (map? x) (contains? x :db/id) (== (count x) 1))                ; It is an object ref...
                    (or (d/q `[:find ?id . :where [~(:db/id x) :resource/id ?id]] conn) ; ...return keyword if it is a resource...
                        (subobj (dp/pull conn '[*] (:db/id x)))),                       ; ...otherwise it is some other structure.
                    (map? x) (reduce-kv (fn [m k v] (assoc m k (subobj v))) {} x),
                    (vector? x) (mapv subobj x),
                    :else x))
            (rem-db-ids [x]
              (cond (map? x) (->> (dissoc x :db/id) (reduce-kv (fn [m k v] (assoc m k (rem-db-ids v))) {})),
                    (vector? x) (mapv rem-db-ids x)
                    :else x))]
      (cond-> (reduce-kv (fn [m k v] (assoc m k (subobj v))) {} obj)
        (not keep-db-ids?) rem-db-ids))))

(defn resource-ids ; ToDo should/could this be lazy?
  "Return a vector of resource keywords"
  [conn]
  (d/q '[:find [?name ...] :where [_ :resource/id ?name]] @conn))

(defn sources
  "Return maps describing what was loaded"
  [conn & {:keys [l2s]}]
  (let [eids (d/q '[:find [?e ...] :where [?e :source/long-name _]] @conn)
        maps (dp/pull-many @conn '[*] eids)]
    (if l2s
      (->> maps (reduce (fn [res m] (assoc res (:source/long-name m) (:source/short-name m))) {}))
      maps)))

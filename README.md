# owl-db-tools - a library for reading OWL into a Datahike database

This library uses clojure-wrapped [Apache Jena](https://jena.apache.org/) to read OWL ontologies 
into a [Datahike](https://datahike.io/) database. 

The library is currently in its early stages of development, though it should be usable with not much effort. 

## Usage

There are three steps to getting started: 
   1. configuring the database,
   2. specifying the data to store, and
   3. creating the database. 

### Configuring the database

There are several persistent DB options described in the [Datahike database configuration docs](https://cljdoc.org/d/io.replikativ/datahike/0.3.6/doc/datahike-database-configuration).
The example shown here writes a persistent file-based DB to `/tmp/datahike-owl-db`.

```clojure
(def db-cfg {:store {:backend :file :path "/tmp/datahike-owl-db"
             :keep-history? false
             :schema-flexibility :write})
```

### Specifying the data to store

Data sources are defined as map entries in nested maps keyed by a prefix (string) that is used as the namespace of a keywords naming each resource associated with the sources URI.
For example, the following defines two sources; 
the first is DOLCE-Lite, retrieved from a remote location;
the second is a local ontology in turtle syntax. 

```clojure
(def onto-sources
  {"dol"   {:uri "http://www.ontologydesignpatterns.org/ont/dlp/DOLCE-Lite.owl"},
   "mod"   {:uri "http://modelmeth.nist.gov/modeling", :access "data/modeling.ttl", :format :turtle}})
```

The key of the outer map defines a shortname for the resource; it provides a namespace for unique identifiers used in the DB in lieu of the IRI string. 
The value of the outer map is a map providing the details about the source file to be read. The map keys are defined below. 
In the example, the resource http://www.ontologydesignpatterns.org/ont/dlp/DOLCE-Lite.owl#state will be stored as an entity with :resource/ident = :dol/state. 

The following keywords are used in the sources:
 * `:uri` the URI of the OWL file to be read, 
 * `:access` a pathname to a local copy of the OWL file (for use where it won't be found at the provided URI, for example).
 * `:format` the format of the content. This defaults to :rdfxml. Presumably, any format for which JENA is capable will be read, but the only 
 two tested are :rdfxml and :turtle. 
 * `:ref-only?` this is used suppress reading content but nonethess establish a relationship between a namespace prefix and a URI.
 There is a default set of these that can be overidden with values from the sources you provide (such as `onto-sources` above):
 
```clojure
{"daml"  {:uri "http://www.daml.org/2001/03/daml+oil"       :ref-only? true},
 "dc"    {:uri "http://purl.org/dc/elements/1.1/"           :ref-only? true},
 "owl"   {:uri "http://www.w3.org/2002/07/owl"              :ref-only? true},
 "rdf"   {:uri "http://www.w3.org/1999/02/22-rdf-syntax-ns" :ref-only? true},
 "rdfs"  {:uri "http://www.w3.org/2000/01/rdf-schema"       :ref-only? true},
 "xml"   {:uri "http://www.w3.org/XML/1998/namespace"       :ref-only? true},
 "xsd"   {:uri "http://www.w3.org/2001/XMLSchema"           :ref-only? true}}
```


### Creating the Database

With the database configured and the source defined as described above, you then call ```(owl/create-db! db-cfg onto-sources)``` to create the database. The function returns a call to a connection to the database. A new connection can be acquired at any time by calling the fucntion again without the :rebuild? argument. 

```clojure
(require '[pdenno.owl-db-tools.core :as owl])

(owl/create-db! db-cfg onto-sources
                :rebuild? true
                :check-sites ["http://ontologydesignpatterns.org/wiki/Main_Page"])
```

The function create-db! takes the following optional keyword arguments:

 * `:rebuild?` if `true` reads the sources, otherwise presumably the database exists and a connection to it is returned.

 * `:check-sites` is a collection of sites (their URIs) that are sources for ontologies. 
 You can use this to abort reading when a source site is not available. This can be used only when `rebuild?` is true. 
 * `:check-sites-timeout` is the number of milliseconds to wait for a response from a check-site. (Defaults to 15000.)
   Of course, this argument is relevant only when `rebuild?` is true. 

Additional actions on the database are described in the [Datahike readme](https://cljdoc.org/d/io.replikativ/datahike/0.3.6/doc/readme)
and [Datahike API docs](https://cljdoc.org/d/io.replikativ/datahike/0.3.6/api/datahike.api).

### Queries

For the most part, you would use Datahike's query and pull for APIs to access the data. 
For example, you can retrieve all the classes from the DOLCE namespace in the example in the test directory using a datalog query such as the following:

```clojure
(require '[datahike.api :as d])

(->> (d/q '[:find [?v ...] :where [_ :resource/id ?v]] @conn) 
     (filter #(= "dol" (namespace %))) sort)

; Returns
(:dol/abstract  :dol/abstract-location  :dol/abstract-location-of  :dol/abstract-quality  :dol/abstract-region  :dol/accomplishment  :dol/achievement...)
```

However, the library also provides `pull-resource` which takes as arguments a resource-id and a database connection.
It returns a map of all the triples associated with the resource-id

```clojure
(owl/pull-resource :dol/perdurant conn)

; Returns
{:owl/disjointWith [:dol/endurant],
 :rdf/type :owl/Class,
 :rdfs/comment
 ["Perdurants (AKA occurrences) comprise what are variously called events, processes, phenomena..."],
 :rdfs/subClassOf
 [:dol/spatio-temporal-particular
  {:owl/onProperty :dol/has-quality, :owl/someValuesFrom [:dol/temporal-location_q], :rdf/type :owl/Restriction}
  {:owl/onProperty :dol/participant, :owl/someValuesFrom [:dol/endurant], :rdf/type :owl/Restriction}
  {:owl/allValuesFrom [:dol/perdurant], :owl/onProperty :dol/part, :rdf/type :owl/Restriction}
  {:owl/allValuesFrom [:dol/temporal-quality], :owl/onProperty :dol/has-quality, :rdf/type :owl/Restriction}
  {:owl/allValuesFrom [:dol/perdurant], :owl/onProperty :dol/specific-constant-constituent, :rdf/type :owl/Restriction}],
 :resource/id :dol/perdurant}
```

You can specify `:keep-db-ids? true` in the call if you would like the result to include database IDs of the returned structure's elements.

## Database Schema

The database is structured as shown. 
Details about such  schema can be found in the [Datahike schema docs](https://cljdoc.org/d/io.replikativ/datahike/0.3.6/doc/schema). 

```clojure
  [#:db{:ident :resource/id  :cardinality :db.cardinality/one :valueType :db.type/keyword :unique :db.unique/identity}

   ;; multi-valued properties
   #:db{:ident :owl/allValuesFrom      :cardinality :db.cardinality/many :valueType :db.type/ref}
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
  [#:db{:ident :rdf/type :cardinality :db.cardinality/one :valueType :db.type/keyword}])
```

## To Do

* Ontologies imported by `owl:imports` are not automatically loaded. If you want them, 
  you must reference them in the call to `create-db!`. 

* There is not yet a distributed jar file (e.g. on clojars). 

* Some simplification of the database to a more usable logical structure might be in order. 
  For example, I don't think there is any reason to have :temp/ resources. 
  In this regard I've only eliminated :rdf/List so far. 

## Disclaimer

This software was developed by [NIST](http://nist/gov). [This disclaimer](https://www.nist.gov/el/software-disclaimer) applies. 

## References

[1]: [Denno, P.; Kim, D. B., "Integrating views of properties in models of unit manufacturing processes"," International Journal of Computer Integrated Manufacturing Vol, 20, Issue 9, 2016."](https://www.tandfonline.com/doi/full/10.1080/0951192X.2015.1130259?scroll=top&needAccess=true)

# Contact Us

<a target="_blank" href="mailto:peter.denno@nist.gov">Peter Denno (peter.denno ( at ) nist.gov</a>









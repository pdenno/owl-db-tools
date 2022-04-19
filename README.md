# owl-db-tools - a library for reading OWL into a Datahike database

This library uses Clojure-wrapped [Apache Jena](https://jena.apache.org/) to read OWL ontologies
into a [Datahike](https://datahike.io/) database.

The library is still young, though its basic features have been tested.

[![Clojars Project](https://img.shields.io/clojars/v/com.github.pdenno/owl-db-tools.svg)](https://clojars.org/com.github.pdenno/owl-db-tools)

# Usage

There are three steps to getting started:
   1. configuring the database,
   2. specifying the data to store, and
   3. creating the database.

## Configuring the database

There are several persistent DB options described in the [Datahike database configuration docs](https://cljdoc.org/d/io.replikativ/datahike/0.3.6/doc/datahike-database-configuration).
The example shown here writes a persistent file-based DB to `/tmp/datahike-owl-db`.

```clojure
(def db-cfg {:store {:backend :file :path "/tmp/datahike-owl-db"
			 :keep-history? false
			 :schema-flexibility :write})
```
There are also examples in the test directory.

## Specifying the data to store

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
In the example, the resource http://www.ontologydesignpatterns.org/ont/dlp/DOLCE-Lite.owl#state will be stored as an entity with :resource/iri = :dol/state.

The following keywords are used in the sources:
 * `:uri` the URI of the OWL file to be read,
 * `:access` a pathname to a local copy of the OWL file (for use where the data is local).
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
Examples of the use of sources can be found in the test directory.

## Creating the Database

With the database configured and the source defined as described above, you then call ```(owl/create-db! db-cfg onto-sources)``` to create the database. The function returns a connection objct to the database; it also sets the dynamic variable `*conn*` in the core namespace to this connection object. A new connection can be acquired at any time by calling the fucntion again without the :rebuild? argument. It can also be acquired directly from Datahike by calling `(datahike.api/connect <db-config-map-as-described-above>)`, which returns an atom containing the connection object.

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

 * `:user-attrs` a vector of Datahike attribute properties (see the section on 'Database Schema' below) to override the default attributes,
 or attributes learned while reading data.

Additional actions on the database are described in the [Datahike readme](https://cljdoc.org/d/io.replikativ/datahike/0.3.6/doc/readme)
and [Datahike API docs](https://cljdoc.org/d/io.replikativ/datahike/0.3.6/api/datahike.api).

# API for queries

The database can be queried directly using Datahike's `query` and `pull` APIs, or using Pathom3 and the
Pathom3 resolvers automatically generated for the attributes of the OWL DB read.
A typical the Datahike query is depicted below paired with filter to get all the RDF resources defined in the DOLCE namespace of the example database.

```clojure
(require '[datahike.api :as d])

(->> (d/q '[:find [?v ...] :where [_ :resource/iri ?v]] @conn)
	 (filter #(= "dol" (namespace %))) sort)

; Returns
(:dol/abstract :dol/abstract-location :dol/abstract-location-of :dol/abstract-quality :dol/abstract-region :dol/accomplishment :dol/achievement...)
```

## Pathom3-based API

[Pathom](https://pathom3.wsscode.com/) is a powerful query language similar to GraphQL.
With Pathom, you specify the shape of the data you wish to acquire and let its planner do the work of composing a query that provides the data.
The use of Pathom positions owl-db-tools to be used as a remote client.
Pathom's documentation is quite good, so only a simple example is provided here.

```clojure
(in-ns 'pdenno.owl-db-tools.resolvers)

(def owl-db (register-resolvers! *conn*)) ; Create the basic attribute resolvers for your database.

(owl-db [{[:resource/iri :info/mapped-to] [:rdf/type :rdfs/domain :rdfs/subPropertyOf]}])

;;; Returns the following:
{[:resource/iri :info/mapped-to]
	{:rdf/type :owl/ObjectProperty,
	 :rdfs/domain [:dol/particular],
	 :rdfs/subPropertyOf :dol/mediated-relation}}
```
There is also a Pathom resolver for obtaining the names of all the RDF resource in the database. 
This resolver provides an optional [Pathom parameter](https://pathom3.wsscode.com/docs/resolvers/#parameters) that allows
filtering, for example, to retrieve all the names in a given namespace:

```clojure
(owl-db '[(:ontology/context {:filter-by {:attr :resource/namespace :val "dol"}})])
```
The `:filter-by` parameter may be a vector of such `{:attr ... :val ...}` maps, but keep in mind that many 
resource attributes are references (to accommodate expressions). Thus, the following won't work:


```clojure
(owl-db '[(:owl/db {:filter-by [{:attr :resource/namespace :val "dol"}  ; Won't work. 
                                {:attr :rdf/type :val :owl/Class}]})])  ; :rdf/type is a DB reference, not a value such as :owl/Class.

```
For such activities, it is better to use the Datahike interfaces.

### `pull-resource`

`pull-resource` is a convenience function in the resolvers namespace that wraps a Pathom3 resolver.
It returns a map of all the triples associated with an RDF resource.
It takes two required arguments the resource keyword and the connection object.

You can specify `:keep-db-ids? true` in the call if you would like the result to include database IDs of the returned structure's elements.

```clojure
(res/pull-resource :dol/perdurant *conn*)

; Returns
{:resource/iri :dol/perdurant,
 :resource/name "perdurant",
 :resource/namespace "dol",
 :owl/disjointWith [:dol/endurant :dol/abstract :dol/quality],
 :rdf/type :owl/Class,
 :rdfs/comment
 ["Perdurants (AKA occurrences) comprise what are variously called events, processes, phenomena..."],
 :rdfs/subClassOf
 [:dol/spatio-temporal-particular
  {:owl/onProperty :dol/has-quality, :owl/allValuesFrom [:dol/temporal-quality], :rdf/type :owl/Restriction}
  {:owl/onProperty :dol/has-quality, :owl/someValuesFrom [:dol/temporal-location_q], :rdf/type :owl/Restriction}
  {:owl/onProperty :dol/part, :owl/allValuesFrom [:dol/perdurant], :rdf/type :owl/Restriction}
  {:owl/onProperty :dol/participant, :owl/someValuesFrom [:dol/endurant], :rdf/type :owl/Restriction}
  {:owl/onProperty :dol/specific-constant-constituent, :owl/allValuesFrom [:dol/perdurant], :rdf/type :owl/Restriction}]}
```

### `resource-ids`

`resource-ids` (in the core namespace) takes one argument, the database connection and returns a vector of resource IDs (namespaced keyword).

### `sources`

`sources` (in the core namespace) takes one required argument, the database connection and returns a map of  information about sources read.

An optional boolean keyword argument `:l2s` (meaning 'long to short') can be specified to return a simple map of
resource URI strings (the map keys) to short-names used as the namespaces of keyword resource IDs.

### `schema-attributes`

`schema-attributes` (in the core namespace) takes one required argument, the database connection and returns a map of database attribute specs.

An optional keyword argument `:origin` can be provided with one or more elements from the set `#{:all, :learned, :user}`
to filter the result to user-specified, learned, or all attributes. The default is `#{:learned :user}`.

## Database Schema

The initial database schema is as shown below.
The data you load may contain attributes beyond those shown.
If while reading the data, additional attributes are encountered, the data will be studied and a best guess at the
cardinality and type of the data will be made.
An attribute spec will be defined accordingly.
You can alway query to see what attribute specs were defined.
You can use `:user-attrs` on the call to `create-db!` to override the guessing process on an individual attribute basis.


Details about such schema can be found in the [Datahike schema docs](https://cljdoc.org/d/io.replikativ/datahike/0.3.6/doc/schema).

```clojure
(def app-schema
  [#:db{:ident :resource/iri       :cardinality :db.cardinality/one :valueType :db.type/keyword :unique :db.unique/identity}
   #:db{:ident :resource/name      :cardinality :db.cardinality/one :valueType :db.type/string}
   #:db{:ident :resource/namespace :cardinality :db.cardinality/one :valueType :db.type/string} 
   #:db{:ident :source/short-name  :cardinality :db.cardinality/one :valueType :db.type/string  :unique :db.unique/identity}
   #:db{:ident :source/long-name   :cardinality :db.cardinality/one :valueType :db.type/string  :unique :db.unique/identity}
   #:db{:ident :source/loaded?     :cardinality :db.cardinality/one :valueType :db.type/boolean}
   #:db{:ident :box/boolean-val    :cardinality :db.cardinality/one :valueType :db.type/ref}   ; These for useful when
   #:db{:ident :box/keyword-val    :cardinality :db.cardinality/one :valueType :db.type/ref}   ; for example, boxing is necessary,
   #:db{:ident :box/number-val     :cardinality :db.cardinality/one :valueType :db.type/ref}   ; such as when you need to store a
   #:db{:ident :box/string-val     :cardinality :db.cardinality/one :valueType :db.type/ref}   ; ref, but have one of these db.type.
   #:db{:ident :app/origin         :cardinality :db.cardinality/one :valueType :db.type/keyword}])

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
   #:db{:ident :rdf/parseType :cardinality :db.cardinality/one :valueType :db.type/keyword}])
```

## To Do

* Ontologies imported by `owl:imports` are not automatically loaded. If you want them,
  you must reference them in the call to `create-db!`. This might be for the best, since it is a chance to
  define meaningful short names.

## Disclaimer

This software was developed by [NIST](http://nist/gov). [This disclaimer](https://www.nist.gov/el/software-disclaimer) applies.

## References

[1]: [Denno, P.; Kim, D. B., "Integrating views of properties in models of unit manufacturing processes"," International Journal of Computer Integrated Manufacturing Vol, 20, Issue 9, 2016."](https://www.tandfonline.com/doi/full/10.1080/0951192X.2015.1130259?scroll=top&needAccess=true)

# Contact Us

<a target="_blank" href="mailto:peter.denno@nist.gov">Peter Denno (peter.denno ( at ) nist.gov</a>

# owl-tools - a library for reading OWL into a Datahike database

This library uses clojure-wrapped [Apache Jena](https://jena.apache.org/) to read OWL ontologies 
into a [Datahike](https://datahike.io/) database. 

The library is currently in its early stages of development, though it should be usable with not much effort. 


## Usage

The file test/core_test.clj defines a complete example usage. 
Essentially, you combine information about local schema (if any) that look, for example, like this:

```clojure
  {;; My local ontology files...
   "ops"   {:prefix "http://modelmeth.nist.gov/operations"
            :access "data/operations.ttl"
            :format :turtle :in-resources? true}
			... more local file map entries. }
```

with information about remote schema (if any), that look, for example, like this:

```clojure
   [ ["coll"  "http://www.ontologydesignpatterns.org/ont/dlp/Collections.owl"]
    ... more remote files (each a vector of two element: a short name for DB URLs and complete URL to the file).]
```
You then define a database configure. There are several persistent DB options, but the 
example in the test directory uses an in-memory database. 
See the [Datahike database configuration docs](https://cljdoc.org/d/io.replikativ/datahike/0.3.6/doc/datahike-database-configuration) for 
more information about this.

With the database configured and the source defined as described above, you then call ```(owl/create-db! db-cfg onto-sources)```. 
This function takes optional keyword arguments:

 * `:check-sites` is a collection of sites (their URIs) that are sources for ontologies. 
 You can use this to abort reading when a source site is not available.
 * `:check-sites-timeout` is the number of milliseconds to wait for a response from a check-site. (Defaults to 15000.)

Additional actions on the database are described in the [Datahike readme](https://cljdoc.org/d/io.replikativ/datahike/0.3.6/doc/readme)
and [Datahike API docs](https://cljdoc.org/d/io.replikativ/datahike/0.3.6/api/datahike.api).


## Database Schema

The database is structured as shown. 
Details about such  schema can be found in the [Datahike schema docs](https://cljdoc.org/d/io.replikativ/datahike/0.3.6/doc/schema). 

```clojure
(def owl-schema
  [#:db{:ident :resource/id :cardinality :db.cardinality/one :valueType :db.type/keyword :unique :db.unique/identity}
   ;; multi-valued properties
   #:db{:ident :owl/allValuesFrom      :cardinality :db.cardinality/many :valueType :db.type/keyword}
   #:db{:ident :owl/disjointUnionOf    :cardinality :db.cardinality/many :valueType :db.type/keyword}
   #:db{:ident :owl/equivalentClasses  :cardinality :db.cardinality/many :valueType :db.type/keyword}
   #:db{:ident :owl/equivalentProperty :cardinality :db.cardinality/many :valueType :db.type/keyword}
   #:db{:ident :owl/hasKey             :cardinality :db.cardinality/many :valueType :db.type/keyword}
   #:db{:ident :owl/intersectionOf     :cardinality :db.cardinality/many :valueType :db.type/keyword}
   #:db{:ident :owl/members            :cardinality :db.cardinality/many :valueType :db.type/keyword}
   #:db{:ident :owl/onProperties       :cardinality :db.cardinality/many :valueType :db.type/keyword}
   #:db{:ident :owl/oneOf              :cardinality :db.cardinality/many :valueType :db.type/ref}
   #:db{:ident :owl/propertyChainAxiom :cardinality :db.cardinality/many :valueType :db.type/keyword}
   #:db{:ident :owl/sameAs             :cardinality :db.cardinality/many :valueType :db.type/keyword}
   #:db{:ident :owl/someValuesFrom     :cardinality :db.cardinality/many :valueType :db.type/keyword}
   #:db{:ident :owl/unionOf            :cardinality :db.cardinality/many :valueType :db.type/keyword} ; <--  Looks ok to me!
   #:db{:ident :owl/withRestrictions   :cardinality :db.cardinality/many :valueType :db.type/keyword}

   ;; single-valued properties
   #:db{:ident :owl/backwardCompatibleWith :cardinality :db.cardinality/one :valueType :db.type/string}
   #:db{:ident :owl/cardinality            :cardinality :db.cardinality/one :valueType :db.type/number} ; was long
   #:db{:ident :owl/complementOf           :cardinality :db.cardinality/one :valueType :db.type/keyword}
   #:db{:ident :owl/disjointWith           :cardinality :db.cardinality/one :valueType :db.type/keyword}
   #:db{:ident :owl/equivalentClass        :cardinality :db.cardinality/one :valueType :db.type/keyword}
   #:db{:ident :owl/hasValue               :cardinality :db.cardinality/one :valueType :db.type/boolean}
   #:db{:ident :owl/imports                :cardinality :db.cardinality/one :valueType :db.type/keyword}
   #:db{:ident :owl/inverseOf              :cardinality :db.cardinality/one :valueType :db.type/keyword}
   #:db{:ident :owl/minCardinality         :cardinality :db.cardinality/one :valueType :db.type/number} ; was long
   #:db{:ident :owl/onProperty             :cardinality :db.cardinality/one :valueType :db.type/keyword}
   #:db{:ident :owl/versionInfo            :cardinality :db.cardinality/one :valueType :db.type/string}

   #:db{:ident :owl/string-val     :cardinality :db.cardinality/one :valueType :db.type/string}
   #:db{:ident :owl/keyword-val    :cardinality :db.cardinality/one :valueType :db.type/keyword}
   #:db{:ident :owl/number-val     :cardinality :db.cardinality/one :valueType :db.type/number}
   #:db{:ident :owl/boolean-val    :cardinality :db.cardinality/one :valueType :db.type/boolean}])   

(def rdfs-schema
  [#:db{:ident :rdfs/domain        :cardinality :db.cardinality/many :valueType :db.type/keyword}
   #:db{:ident :rdfs/range         :cardinality :db.cardinality/many :valueType :db.type/keyword}
   #:db{:ident :rdfs/comment       :cardinality :db.cardinality/many :valueType :db.type/string}
   #:db{:ident :rdfs/label         :cardinality :db.cardinality/many :valueType :db.type/string}
   #:db{:ident :rdfs/subClassOf    :cardinality :db.cardinality/many :valueType :db.type/keyword}
   #:db{:ident :rdfs/label         :cardinality :db.cardinality/one  :valueType :db.type/string}
   #:db{:ident :rdfs/subPropertyOf :cardinality :db.cardinality/one  :valueType :db.type/keyword}])

(def rdf-schema
  [#:db{:ident :rdf/type :cardinality :db.cardinality/one :valueType :db.type/keyword}])
```

## To Do

The library isn't quite a proper clojure library yet: 
(1) it does logging (using timbre), and 
(2) there is not yet a distributed jar file (e.g. on clojars). 

Some simplification of the database to a more usable logical structure might be in order. 
For example, I don't think there is any reason to have :temp/ resources. I've only eliminated :rdf/List so far.

## Disclaimer
The use of any software or hardware by the project does not imply a recommendation or endorsement by NIST.

The use of the project results in other software or hardware products does not imply a recommendation or endorsement by NIST of those products.

We would appreciate acknowledgement if any of the project results are used, however, the use of the NIST logo is not allowed.

NIST-developed software is provided by NIST as a public service. You may use, copy and distribute copies of the software in any medium, provided that you keep intact this entire notice. You may improve, modify and create derivative works of the software or any portion of the software, and you may copy and distribute such modifications or works. Modified works should carry a notice stating that you changed the software and should note the date and nature of any such change. Please explicitly acknowledge the National Institute of Standards and Technology as the source of the software.

NIST-developed software is expressly provided “AS IS.” NIST MAKES NO WARRANTY OF ANY KIND, EXPRESS, IMPLIED, IN FACT OR ARISING BY OPERATION OF LAW, INCLUDING, WITHOUT LIMITATION, THE IMPLIED WARRANTY OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE, NON-INFRINGEMENT AND DATA ACCURACY. NIST NEITHER REPRESENTS NOR WARRANTS THAT THE OPERATION OF THE SOFTWARE WILL BE UNINTERRUPTED OR ERROR-FREE, OR THAT ANY DEFECTS WILL BE CORRECTED. NIST DOES NOT WARRANT OR MAKE ANY REPRESENTATIONS REGARDING THE USE OF THE SOFTWARE OR THE RESULTS THEREOF, INCLUDING BUT NOT LIMITED TO THE CORRECTNESS, ACCURACY, RELIABILITY, OR USEFULNESS OF THE SOFTWARE.

You are solely responsible for determining the appropriateness of using and distributing the software and you assume all risks associated with its use, including but not limited to the risks and costs of program errors, compliance with applicable laws, damage to or loss of data, programs or equipment, and the unavailability or interruption of operation. This software is not intended to be used in any situation where a failure could cause risk of injury or damage to property. The software developed by NIST employees is not subject to copyright protection within the United States.

## References

[1]: [Denno, P.; Kim, D. B., "Integrating views of properties in models of unit manufacturing processes"," International Journal of Computer Integrated Manufacturing Vol, 20, Issue 9, 2016."](https://www.tandfonline.com/doi/full/10.1080/0951192X.2015.1130259?scroll=top&needAccess=true)

# Contact Us

<a target="_blank" href="mailto:peter.denno@nist.gov">Peter Denno (peter.denno ( at ) nist.gov</a>









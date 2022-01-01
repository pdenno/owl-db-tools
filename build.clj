(ns build
  (:require [clojure.tools.build.api :as b]))

;;; THE FOLLOWING IS WHAT I USE. (manual copy because clj -X:deps mvn-install can't find the jar.)
;;;
;;;    clj -X:jar 
;;;    clj -X:install
;;;    mkdir -p ~/.m2/repository/com/github/pdenno/owl-db-tools/1.0.22/
;;;    cp owl-db-tools-1.0.22.jar pom.xml ~/.m2/repository/com/github/pdenno/owl-db-tools/1.0.22/

;;; Do I really need to change directories here? (I changed above to 'cp' 
;;; pushd ~/.m2/repository/com/github/pdenno/owl-db-tools/1.0.22/ 
;;; CLOJARS_USERNAME=pdenno
;;; CLOJARS_PASSWORD=
;;; clojure -M:project/clojars owl-db-tools-1.0.22.jar



;;; I don't yet use any of code in this file. It would work like this
;;; https://clojure.org/guides/tools_build
;;; Run this with the following:
;;;   clj -T:build clean
;;;   clj -T:build jar
;;;   clj -T:build install


(def lib 'com.github.pdenno/owl-db-tools)
(def version (format "1.0.%s" (b/git-count-revs nil)))
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))
(def jar-file (format "target/%s-%s.jar" (name lib) version))

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar [_]
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis basis
                :src-dirs ["src/main"]})
  (b/copy-dir {:src-dirs ["src/main"] :target-dir class-dir})
  (b/jar {:class-dir class-dir :jar-file jar-file}))

;;; :basis - required, used for :mvn/local-repo
(defn install [_]
  (let [opts {:lib lib :basis basis :jar-file jar-file :class-dir class-dir :version version}]
    (b/install opts)))

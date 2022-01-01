(ns build
  (:require [clojure.tools.build.api :as b]))

;;; I don't yet use any of code in this file.
;;; I'm using something like the Practicalli deps.edn. (See the deps.edn)
;;; If I were to use this, it would work as follows (See also https://clojure.org/guides/tools_build):
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

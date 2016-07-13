(defproject aerospike "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :repositories {"local" ~(str (.toURI (java.io.File. "$HOME/.m2/repository")))}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [com.mogujie.mst/jepsen "0.1.2"]
                 [com.aerospike/aerospike-client "3.1.0"]])

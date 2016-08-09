(defproject tair "0.1.0-SNAPSHOT"
  :description "tair jepsen project"
  :url "https://github.com/lotair/tair"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :repositories {"local" ~(str (.toURI (java.io.File. "$HOME/.m2/repository")))}
  :dependencies [
                 [org.clojure/clojure "1.6.0"]
                 [com.mogujie.mst/jepsen "0.1.2"]
                 [com.taobao.tair/tair-client "2.3.4"]
                 [selmer "1.0.7"]
                 [crypto-random "1.2.0"]
                 ]
  :main tair.core)

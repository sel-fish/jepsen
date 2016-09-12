(ns redis.core
  (:require [jepsen.tests :as tests]
            [clojure.tools.logging :refer :all]
            [jepsen
             [db :as db]
             [control :as c]
             [nemesis :as nemesis]
             [tests :as tests]
             [generator :as generator]
             [util :refer [timeout]]
             [client :as client]
             [generator :as gen]
             [checker :as checker]
             ]
            [selmer.parser :as parser]
            [knossos.model :as model]
            [clojure.set :as set]
            [jepsen.os.debian :as debian]
            [jepsen.control.net :as net]
            [crypto.random :as random]
            ))

(defn classify [nodes]
  "classify nodes to different roles"
  )

(defn init-redis-infos [roles]
  "init redis params"
  )

(defn getlist
  "get a list"
  [prefix num]
  (loop [i 1
         l []]
    (if (<= i num)
      (recur (+ i 1)
             (conj l (keyword (str prefix i))))
      (seq l))
    )
  )

(defn redis-sentinel-setup-test
  [version]
  (let [
        nodes (getlist "n" 7)
        roles (classify nodes)
        ]
    (init-redis-infos roles)
    (assoc tests/noop-test
      :name "redis-sentinel-setup-test"
      :ssh {:username "root", :password "root", :port 22}
      :nodes nodes
      :os debian/os
      ;:db (db version)
      ;:client (redis-client)
      )))

(defn -main []
  "I don't say 'Hello World' :) ..."
  (info (getlist "n" 5))
  (info (list :winterfell :riverrun :theeyrie :casterlyrock :highgarden))
  )
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
            [clojure.string :as string]))

(def ^:dynamic *redis-infos* (ref {}))

(defn update-nodes-info
  [nodes-info node info]
  (if (contains? nodes-info node)
    (assoc nodes-info node (conj (node nodes-info) info))
    (assoc nodes-info node (list info))
    )
  )

(defn classify [nodes]
  "classify nodes to different roles"
  (let [
        sentinel-num 3
        redis-slave-num 1
        sentinels (take sentinel-num nodes)
        rest (if (> (count nodes) (* 2 sentinel-num))
               (remove (set sentinels) nodes)
               nodes)
        redis-group-num (count rest)
        servers (take redis-group-num rest)
        ]
    (def nodes-info {})
    (loop [
           i 0
           redis-masters []
           ]
      (if (< i redis-group-num)
        (let [
              redis-master (nth servers (mod i redis-group-num))
              slave-info {:isslave 1, :master redis-master}
              master-info {:isslave 0}
              ]
          (def nodes-info (update-nodes-info nodes-info redis-master master-info))
          (doseq [slave-index (range redis-slave-num)]
            (let [redis-slave (nth servers (mod (+ 1 (+ i slave-index)) redis-group-num))]
              (def nodes-info (update-nodes-info nodes-info redis-slave slave-info))
              )
            )
          (recur (+ i 1) (conj redis-masters redis-master))
          )
        {
         :sentinels           (into #{} sentinels)
         :servers             servers
         :redis-group-num     redis-group-num
         :redis-masters       (seq redis-masters)
         :nodes-info          nodes-info
         }
        ))))

(defn init-redis-infos [roles]
  "init redis params"
  (def ^:dynamic *redis-infos* (ref (merge @*redis-infos* roles)))
  (dosync (alter *redis-infos* assoc :device "eth0"))
  (dosync (alter *redis-infos* assoc :master-port 6379))
  (dosync (alter *redis-infos* assoc :sentinel-port 26379))
  (dosync (alter *redis-infos* assoc :group-name-prefix "session-cache"))
  (dosync (alter *redis-infos* assoc :ipmap {}))
  (dosync (alter *redis-infos* assoc :inited 0))
  (dosync (alter *redis-infos* assoc :skip-close-cluster 1))
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

(defn check-dir-exist
  "check if dir exist, return 'True' if exist, otherwise 'False'"
  [path]
  (let [command (str "\"if [ -d " path " ]; then echo True; else echo False; fi\"")]
    ;(info command)
    (c/exec :bash :-c (c/lit command))))

(defn check-dir-with-wildcard
  "check if dir exist, return 'True' if exist, otherwise 'False'"
  [path]
  (let [command (str "\"if ls " path " 1>/dev/null 2>&1 ; then echo True; else echo False; fi\"")]
    ;(info command)
    (c/exec :bash :-c (c/lit command))))

(defn install!
  "install redis bin"
  [node version]
  (let [
        exist (check-dir-exist "/root/redis")
        ;exist "False"
        ]
    (when-not (= "True" exist)
      (info node "installing Redis" version)
      (c/upload (str "resources/redis-" version "-debian8.tgz") (str "/tmp/redis.tgz"))
      (c/su
        (c/cd "/tmp"
              (c/exec :tar :xvfz "redis.tgz"))
        (c/exec :rm :-rf (c/lit "/root/redis*"))
        (c/exec :mv (str "/tmp/redis-" version) "/root/")
        (c/exec :mv (str "/root/redis-" version) "/root/redis")
        ))))

(defn configure!
  "Uploads configuration files to the given node."
  [node test version]
  (info node "configure Redis")
  (let [master-port (:master-port @*redis-infos*)
        sentinel-port (:sentinel-port @*redis-infos*)
        sentinel-num (count (:sentinels @*redis-infos*))]
    (c/su
      (if (contains? (:sentinels @*redis-infos*) node)
        (let [redis-masters (:redis-masters @*redis-infos*)
              group-name-prefix (:group-name-prefix @*redis-infos*)]
          (loop [i 0
                 redis-groups []]
            (if (< i (count redis-masters))
              (let [redis-master (nth redis-masters i)
                    redis-group {:redis_group_name (str group-name-prefix i)
                                 :redis_master_ip (redis-master (:ipmap @*redis-infos*))
                                 :redis_master_port master-port
                                 :quronum (+ 1 (quot sentinel-num 2))}]
                (debug redis-group)
                (recur (+ 1 i) (conj redis-groups redis-group)))
              (c/exec :echo (parser/render-file "sentinel.conf"
                                                {:port         sentinel-port,
                                                 :redis_groups (seq redis-groups)})
                      :> (str "/root/redis/etc/sentinel-" (str sentinel-port) ".conf"))
              ))))
      (if (contains? (:nodes-info @*redis-infos*) node)
        (let [node-info (node (:nodes-info @*redis-infos*))]
          (loop [i 0
                 slave-port (+ 1 master-port)]
            (when (< i (count node-info))
              (let [redis-info (nth node-info i)
                    is-slave (= 1 (:isslave redis-info))
                    port (if is-slave slave-port master-port)
                    next-slave-port (if is-slave (+ 1 slave-port) slave-port)
                    master-ip (if is-slave ((:master redis-info) (:ipmap @*redis-infos*)) nil)
                    master-port (if is-slave master-port nil)
                    ]
                (c/exec :echo (parser/render-file "redis.conf"
                                                  {:port        port,
                                                   :maxmemory   "1G",
                                                   :is_slave    is-slave,
                                                   :master_ip   master-ip,
                                                   :master_port master-port})
                        :> (str "/root/redis/etc/redis-" (str port) ".conf"))
                (recur (+ 1 i) next-slave-port)
                )))))
      )))

(defn start!
  "Start redis."
  [node test version]
  (info node "starting Redis")
  (dosync (alter *redis-infos* assoc :inited 1))
  (let [master-port (:master-port @*redis-infos*)
        sentinel-port (:sentinel-port @*redis-infos*)
        sentinel-num (count (:sentinels @*redis-infos*))]
    (c/su
      (c/cd
        (str "/root/redis")
        (if (contains? (:sentinels @*redis-infos*) node)
          (do
            (c/exec :mkdir (str "sentinel-" sentinel-port))
            (c/exec (str "bin/redis-sentinel") (str "etc/sentinel-" sentinel-port ".conf"))
            )
          )
        (if (contains? (:nodes-info @*redis-infos*) node)
          (let [node-info (node (:nodes-info @*redis-infos*))]
            (info "start redis-server...")
            (loop [i 0
                   slave-port (+ 1 master-port)]
              (when (< i (count node-info))
                (let [redis-info (nth node-info i)
                      is-slave (= 1 (:isslave redis-info))
                      redis-port (if is-slave slave-port master-port)
                      next-slave-port (if is-slave (+ 1 slave-port) slave-port)
                      ]
                  (c/exec :mkdir (str "redis-" redis-port))
                  (c/exec (str "bin/redis-server") (str "etc/redis-" redis-port ".conf"))
                  (recur (+ 1 i) next-slave-port)
                  ))))
          )))))

(defn retrieveip
  "retrieve ip and fill in *redis-infos*"
  [node]
  (let [ip (jepsen.control.net/device-ip (:device @*redis-infos*))]
    (dosync (alter *redis-infos* assoc :ipmap (assoc (:ipmap @*redis-infos*) node ip)))))

(defn stop!
  "Stop tair."
  [node version]
  (info node "stop Redis")
  (let [master-port (:master-port @*redis-infos*)]
    (c/su
      (c/cd
        (str "/root/redis")
        (let [is-sentinel (= "True" (check-dir-with-wildcard "sentinel-*"))
              is-redis (= "True" (check-dir-with-wildcard "redis-*"))]
          (if is-sentinel
            (let [sentinel-paths-str (c/exec :ls c/| :grep "sentinel-")
                  sentinel-paths (string/split-lines sentinel-paths-str)]
              (info node "stop redis-sentinel..." sentinel-paths (count sentinel-paths))
              (doseq [sentinel-path sentinel-paths]
                (try
                  (c/cd
                    sentinel-path
                    (c/exec :kill (c/lit "`cat sentinel.pid`")))
                  (catch Exception e
                    (info "exception: " (.getMessage e)))
                  ))))
          (if is-redis
            (let [redis-paths-str (c/exec :ls c/| :grep "redis-")
                  redis-paths (string/split-lines redis-paths-str)]
              (info node "stop redis..." redis-paths (count redis-paths))
              (doseq [redis-path redis-paths]
                (try
                  (c/cd
                    redis-path
                    (c/exec :kill (c/lit "`cat redis.pid`")))
                  (catch Exception e
                    (info "exception: " (.getMessage e)))
                  ))))
          )
        (c/exec :rm :-rf (c/lit "sentinel-*"))
        (c/exec :rm :-rf (c/lit "redis-*"))
        ))))

(defn db
  "Redis for a particular version."
  [version]
  (reify db/DB
    (setup! [_ test node]
      (info node "setting up Redis")
      (doto node
        (install! version)
        (configure! test version)
        (start! test version)
        ))

    (teardown! [_ test node]
      (info node "tearing down Redis")
      (if (or (= 0 (:inited @*redis-infos*))
              (= 0 (:skip-close-cluster @*redis-infos*)))
        (do
          ; a trick to retrieveip in teardown
          ; as jepsen will run teardown before setup :)
          (retrieveip node)
          (stop! node version)
          )
        (info "skip teardown...")
        )
      )))

(defn redis-sentinel-setup-test
  [version]
  (let [
        nodes (getlist "n" 7)
        ;nodes (list :winterfell :riverrun :theeyrie :casterlyrock :highgarden)
        roles (classify nodes)
        ]
    (init-redis-infos roles)
    (assoc tests/noop-test
      :name "redis-sentinel-setup-test"
      :ssh {:username "root", :password "root", :port 22}
      :nodes nodes
      :os debian/os
      :db (db version)
      )))

(defn -main []
  "I don't say 'Hello World' :) ..."
  (let [
        ;nodes (getlist "n" 7)
        nodes (list :winterfell :riverrun :theeyrie :casterlyrock :highgarden)
        roles (classify nodes)
        ]
    ;(doseq [node [1 2 3]]
    (doseq [node nodes]
      (info node))
    (info "abc")
  ;(info roles)
  ))
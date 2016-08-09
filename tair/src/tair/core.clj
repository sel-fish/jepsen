(ns tair.core
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
            )
  (:import (com.taobao.tair.impl DefaultTairManager)
           (com.taobao.tair Result ResultCode))
  )

(def ^:dynamic *tair-infos* (ref {}))
(def ^:dynamic *tair-keywords-info* {})
(def ^:dynamic *tair-keywords-counter* (ref {}))

(defn classify
  [nodes]
  "classify nodes to cs or ds"
  (do
    (def cslist [])
    (def dsset #{})
    (def roles {:ds nil, :cs nil})
    (doseq [i (range (count nodes))]
      (let [node (nth nodes i)]
        (do
          (if (<= i 1)
            (def cslist (concat cslist `(~node))))
          (def dsset (concat dsset `(~node)))
          )))
    (def roles (assoc-in roles [:ds] dsset))
    (def roles (assoc-in roles [:cs] cslist))
    roles))

(defn in?
  "true if coll contains elm"
  [coll elm]
  (some #(= elm %) coll))

(defn init-tair-infos
  "init tair infos"
  [roles]
  (dosync (alter *tair-infos* assoc :ds (:ds roles)))
  (dosync (alter *tair-infos* assoc :cs (:cs roles)))
  (dosync (alter *tair-infos* assoc :device "eth0"))
  (dosync (alter *tair-infos* assoc :masterip nil))
  (dosync (alter *tair-infos* assoc :slaveip nil))
  (dosync (alter *tair-infos* assoc :groupname "group_fenqi"))
  (dosync (alter *tair-infos* assoc :copycnt 2))
  (dosync (alter *tair-infos* assoc :maxns 1023))
  (dosync (alter *tair-infos* assoc :bucketcnt 100))
  (dosync (alter *tair-infos* assoc :storage-engine "ldb"))
  (dosync (alter *tair-infos* assoc :iplist []))
  (dosync (alter *tair-infos* assoc :inited 0))
  (dosync (alter *tair-infos* assoc :skip-close-cluster 1))
  )

(defn init-tair-keywords
  "init tair keywords info"
  []
  (let [
        need-migrate-info {:file "/root/tair/logs/config.log", :str "need migrate"}
        migrate-done-info {:file "/root/tair/logs/config.log", :str "migrate all done"}
        ]
    (def ^:dynamic *tair-keywords-info* (assoc-in *tair-keywords-info* [:migrate-start] need-migrate-info))
    (def ^:dynamic *tair-keywords-info* (assoc-in *tair-keywords-info* [:migrate-done] migrate-done-info))
    (dosync (alter *tair-keywords-counter* assoc :migrate-start 0))
    (dosync (alter *tair-keywords-counter* assoc :migrate-done 0))
    )
  )

(defn connect
  "Returns a client for the given node. Blocks until the client is available."
  []
  (let [client (DefaultTairManager.)
        _ (doto client
            (.setConfigServerList (list (str (:masterip @*tair-infos*) ":5198")
                                        (str (:slaveip @*tair-infos*) ":5198")))
            (.setGroupName (:groupname @*tair-infos*))
            (.init))]
    client))

(defn record->map
  "Converts a result to a map like"
  [^Result r]
  (when (.getValue r)
    {:rc      (.getRc r)
     :value   (.getValue r)}))

(defn w [_ _] {:type :invoke, :f :write, :value :put})
(defn r [_ _] {:type :invoke, :f :read, :value :get})
(defn add [_ _] {:type :invoke, :f :add, :value 1})

(defrecord TairClient [client namespace key]
  client/Client
  (setup! [this test node]
    (Thread/sleep 10000)
    (let [client (connect)]
      (assoc this :client client)))

  (invoke! [this test op]
    (timeout 5000
             (assoc op :type :info, :error :timeout)
             (case (:f op)
               :read (assoc op
                       :type :ok,
                       :value (-> client (.get namespace key) record->map :value (.getValue)))
               :write (let [
                            ns (rand-int (:maxns @*tair-infos*))
                            k (random/base64 32)
                            v (random/base64 32)
                            rc (-> client (.put ns k v))
                            code (-> rc (.getCode))
                            ]
                        (if [= 0 code]
                          (assoc op :type :ok, :value :success)
                          (assoc op :type :info, :error (keyword (-> rc (.getRc) (.getMessage))))
                          ))
               :add (let [
                          record-map (-> client (.incr namespace key (:value op) 0 0) record->map)
                          code (-> (:rc record-map) (.getCode))
                          ]
                      (if [= 0 code]
                        (assoc op :type :ok)
                        (assoc op :type :info, :error (keyword (-> (:rc record-map) (.getMessage))))
                        ))
               )))

  (teardown! [_ test]))

(defn tair-counter-client
  "manipulate a counter key"
  []
  (TairClient. nil (rand-int 1023) "counter-key"))

(defn tair-common-client
  "common tair client"
  []
  (TairClient. nil 0 ""))

(defn install!
  "Installs tair on the given nodes."
  [node version]
  ; pay attention that "-2" is just the minor build version
  ; change it please
  (when-not (= (str version "-2")
               (debian/installed-version "tair"))
    (c/upload (str "resources/tair-2.6.0-debian8.tgz") (str "/tmp/tair.tgz"))
    (debian/install ["libgoogle-perftools4"])
    (c/su
      (debian/uninstall! ["tair"])
      (info node "installing Tair" version)
      (c/cd "/tmp"
            ; download is too slow, use upload instead
            ;(c/exec :wget :-O (str "tair.tgz")
            ;        (str "https://github.com/lotair/tair/releases/download/jepsen/tair-" version
            ;             "-debian8.tgz"))
            (c/exec :tar :xvfz "tair.tgz")))
    (c/cd (str "/tmp/tair-" version "-debian8")
          (c/exec :dpkg :-i (c/lit "tair*.deb")))
    ; create soft link
    ; TODO test if the path exist
    (c/su (c/exec :ln :-s (str "/usr/local/tair-" version) (str "/root/tair")))
    )
  )

(defn configure!
  "Uploads configuration files to the given node."
  [node test version]
  (info node "configure Tair")
  (c/su
    (c/exec :echo (parser/render-file "dataserver.conf" @*tair-infos*)
            :> (str "/root/tair/etc/dataserver.conf"))
    (if (in? (:cs @*tair-infos*) node)
      (do
        (c/exec :echo (parser/render-file "configserver.conf" @*tair-infos*)
                :> (str "/root/tair/etc/configserver.conf"))
        (c/exec :echo (parser/render-file "group.conf" @*tair-infos*)
                :> (str "/root/tair/etc/group.conf"))))))

(defn start!
  "Start tair."
  [node test version]
  (info node "starting Tair")
  (dosync (alter *tair-infos* assoc :inited 1))
  (c/su
    (c/cd (str "/root/tair")
          (c/exec :bash (str "tair.sh") (str "start_ds"))
          (if (in? (:cs @*tair-infos*) node)
            (c/exec :bash (str "tair.sh") (str "start_cs"))))
    ))

(defn retrieveip
  "retrieve ip and fill in *tairinfos*"
  [node]
  (info node (net/device-ip (:device @*tair-infos*)))
  (let [ip (net/device-ip (:device @*tair-infos*))]
    (if (in? (:cs @*tair-infos*) node)
      (if (= node (nth (:cs @*tair-infos*) 0))
        ((dosync (alter *tair-infos* assoc :masterip ip))
          (if (nil? (:slaveip @*tair-infos*))
            (dosync (alter *tair-infos* assoc :slaveip ip))))
        (dosync (alter *tair-infos* assoc :slaveip ip))
        ))
    (info "add" ip "to iplist")
    (dosync (alter *tair-infos* assoc :iplist (concat (:iplist @*tair-infos*) `(~ip))))
    (info "iplist is" (:iplist @*tair-infos*))))

(defn stop!
  "Stop tair."
  [node version]
  (info node "stop Tair")
  (c/su
    (c/cd (str "/root/tair")
          (c/exec :bash (str "tair.sh") (str "stop_ds"))
          ; TODO test if cs exist
          (c/exec :bash (str "tair.sh") (str "stop_cs"))
          (c/exec :bash (str "tair.sh") (str "clean"))
          (c/exec :rm :-rf (c/lit (str "ldbdata*")))
          )))

(defn db
  "Tair DB for a particular version."
  [version]
  (reify db/DB
    (setup! [_ test node]
      (info node "setting up Tair")
      (doto node
        (install! version)
        (configure! test version)
        (start! test version)
        ))

    (teardown! [_ test node]
      (info node "tearing down Tair")
      (if (or (= 0 (:inited @*tair-infos*))
              (= 0 (:skip-close-cluster @*tair-infos*)))
        (do
          ; a trick to retrieveip in teardown
          ; as jepsen will run teardown before setup :)
          (retrieveip node)
          (stop! node version)
          )
        (info "skip teardown...")
        ))))

(defn std-gen
  "Takes a client generator and wraps it in a typical schedule and nemesis
  causing failover."
  [gen]
  (gen/phases
    (->> gen
         (gen/nemesis
           (gen/seq (cycle [(gen/sleep 2)
                            {:type :info :f :start}
                            (gen/sleep 10)
                            {:type :info :f :stop}])))
         (gen/time-limit 60))
    ; Recover
    (gen/nemesis
      (gen/once {:type :info :f :stop}))
    ; Wait for resumption of normal ops
    (gen/clients
      (->> gen
           (gen/time-limit 5)))))

(defn tair-counter-test
  [version]
  (let [
        ; nodes (list :yunkai)
        nodes (list :winterfell :riverrun :theeyrie :casterlyrock :highgarden)
        roles (classify nodes)
        ]
    (init-tair-infos roles)
    (assoc tests/noop-test
      :name "tair-counter-test"
      :ssh {:username "root", :password "root", :port 22}
      :nodes nodes
      :os debian/os
      :db (db version)
      :client (tair-counter-client)
      :nemesis (nemesis/partition-random-halves)
      :generator (->>
                   (repeat 100 add)
                   (cons r)
                   gen/mix
                   (gen/delay 1/100)
                   std-gen)
      :model (model/cas-register)
      :checker (checker/compose {:counter checker/counter
                                 :perf    (checker/perf)})
      )))

(defn random-get-one-ds
  "return a random node"
  [xs]
  (let [x (rand-nth xs)]
    (info "pick" x)
    (vector x)))

(defn startds!
  [node]
  "Start Tair dataserver."
  (info node "start Tair")
  (c/su
    (c/cd (str "/root/tair")
          (c/exec :bash (str "tair.sh") (str "start_ds")))))

(defn stopds!
  [node]
  "Stop Tair dataserver."
  (info node "stop Tair")
  (c/su
    (c/cd (str "/root/tair")
          (c/exec :bash (str "tair.sh") (str "stop_ds")))))

(def one-ds-offline-nemesis
  "A nemesis that crashes a random dataserver in Tair."
  (nemesis/node-start-stopper
    random-get-one-ds
    (fn start [test node] (stopds! node) [:killed node])
    (fn stop [test node] (startds! node) [:restarted node])))

(defn get-remote-keyword-count
  "get keyword count from file"
  [file keyword]
  (let [count (c/exec :grep (str keyword) (str file) (c/lit "|wc -l"))]
    (debug file keyword count)
    count))

(defn get-keyword-count
  "get keyword info from mastercs"
  [keyword]
  (let [mastercs (nth (:cs @*tair-infos*) 0)
        keyword-info (keyword *tair-keywords-info*)
        keyword-count (c/on mastercs (get-remote-keyword-count (:file keyword-info) (:str keyword-info)))
        ]
    (read-string keyword-count)
    ))

(defn gen-get-keyword-count
  "get keyword from log"
  [keyword]
  (reify generator/Generator
    (op [gen test process]
      (let [count (get-keyword-count keyword)]
        (info "get count of " keyword ": " count)
        (dosync (alter *tair-keywords-counter* assoc keyword count)))
      nil)))

(defn gen-wait-for-keyword
  "wait until the keyword appear in timeout limited"
  [keyword timeout-secs]
  (reify generator/Generator
    (op [gen test process]
      (timeout (* 1000 timeout-secs)
               (throw (RuntimeException.
                        (str "Timed out after "
                             timeout-secs
                             " s waiting for " keyword)))
               (let [expect (+ 1 (keyword @*tair-keywords-counter*))]
                 (loop []
                   (let [cur (get-keyword-count keyword)]
                     (if (not= 0 (- expect cur))
                       (do
                         (info keyword "expect: " expect ", but: " cur ", sleep 10s and retry")
                         (Thread/sleep 10000)
                         (recur))
                       (info keyword "get expect value" expect)
                       )))))
      nil)))

(defn wait-nemesis-done
  "as nemesis always cause migrate, provide this func to support this scenerio"
  ([gen] (wait-nemesis-done gen 300))
  ([gen timeout]
   (gen/seq [
             (gen-get-keyword-count :migrate-start)
             (gen-get-keyword-count :migrate-done)
             gen
             (gen-wait-for-keyword :migrate-start timeout)
             (gen-wait-for-keyword :migrate-done timeout)
             ]))
  )

(defn tair-ds-offline-test
  [version]
  (let [
        nodes (list :winterfell :riverrun :theeyrie :casterlyrock :highgarden)
        roles (classify nodes)
        ]
    (init-tair-infos roles)
    (init-tair-keywords)
    (assoc tests/noop-test
      :name tair-ds-offline-test
      :ssh {:username "root", :password "root", :port 22}
      :nodes nodes
      :os debian/os
      :db (db version)
      :client (tair-common-client)
      :nemesis one-ds-offline-nemesis
      :generator (gen/phases
                   (gen/sleep 30)
                   (gen/nemesis
                     (wait-nemesis-done {:type :info, :f :start, :value :stopds}))
                   (->>
                     w
                     (gen/limit 10)
                     (gen/clients))
                   (gen/nemesis
                     (wait-nemesis-done {:type :info, :f :stop, :value :startds}))
                   )
      )))

(defn split-one-ds
  "Split one dataserver off from the rest"
  [coll]
  (let [loner (rand-nth (seq (set/difference (set coll) (set (:cs @*tair-infos*)))))]
    (info "loner is " loner)
    [[loner] (remove (fn [x] (= x loner)) coll)]))

(defn split-one-cs
  "Split one configserver off from the rest"
  [coll]
  (let [loner (rand-nth (:cs @*tair-infos*))]
    [[loner] (remove (fn [x] (= x loner)) coll)]))

(defn one-ds-split-nemesis
  "split one ds from cluster"
  []
  (nemesis/partitioner (comp nemesis/complete-grudge split-one-ds))
  )

(defn tair-ds-split-test
  [version]
  (let [
        nodes (list :winterfell :riverrun :theeyrie :casterlyrock :highgarden)
        roles (classify nodes)
        ]
    (init-tair-infos roles)
    (init-tair-keywords)
    (assoc tests/noop-test
      :name "tair-ds-split-test"
      :ssh {:username "root", :password "root", :port 22}
      :nodes nodes
      :os debian/os
      :db (db version)
      :client (tair-common-client)
      :nemesis (one-ds-split-nemesis)
      :generator (gen/phases
                   (gen/sleep 30)
                   (gen/nemesis
                     (wait-nemesis-done {:type :info, :f :start, :value :splitds}))
                   (->>
                     w
                     (gen/limit 10)
                     (gen/clients))
                   (gen/nemesis
                     (wait-nemesis-done {:type :info, :f :stop, :value :recoverds}))
                   )
      )))

(defn -main []
  "I don't say 'Hello World' :) ..."
  (let [
        nodes (list :winterfell :riverrun :theeyrie :casterlyrock :highgarden)
        roles (classify nodes)
        keyword :migrate-start
        ]
    (init-tair-infos roles)
    (init-tair-keywords)
    (info (keyword *tair-keywords-info*))
    (info (random/base64 10))
    ))
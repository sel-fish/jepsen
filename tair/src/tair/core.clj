(ns tair.core
  (:require [jepsen.tests :as tests]
            [clojure.tools.logging :refer :all]
            [jepsen
             [core :as jepsen]
             [db :as db]
             [control :as c]
             [tests :as tests]
             [util :refer [timeout]]]
            [jepsen.os.debian :as debian]
            [clojure.string :as str]
            [jepsen.control.net :as net]
            [clojure.java.io :as io]
            [selmer.parser :as parser]
            [jepsen.client :as client]
            [jepsen.generator :as gen]
            [knossos.model :as model]
            [jepsen.checker :as checker])
  (:import (com.taobao.tair.impl DefaultTairManager)
           (com.taobao.tair Result)))

(def ^:dynamic *tairinfos* (ref {}))

(defn w [_ _] {:type :invoke, :f :write, :value (rand-int 5)})
(defn r [_ _] {:type :invoke, :f :read})
(defn add [_ _] {:type :invoke, :f :add, :value 1})

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
          )
        )
      )
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
  (dosync (alter *tairinfos* assoc :ds (:ds roles)))
  (dosync (alter *tairinfos* assoc :cs (:cs roles)))
  (dosync (alter *tairinfos* assoc :device "eth0"))
  (dosync (alter *tairinfos* assoc :masterip nil))
  (dosync (alter *tairinfos* assoc :slaveip nil))
  (dosync (alter *tairinfos* assoc :groupname "group_fenqi"))
  (dosync (alter *tairinfos* assoc :copycnt 1))
  (dosync (alter *tairinfos* assoc :bucketcnt 100))
  (dosync (alter *tairinfos* assoc :storage-engine "ldb"))
  (dosync (alter *tairinfos* assoc :iplist [])))

(defn connect
  "Returns a client for the given node. Blocks until the client is available."
  []
  (let [client (DefaultTairManager.)
        _ (doto client
            (.setConfigServerList (list (str (:masterip @*tairinfos*) ":5198")
                                        (str (:slaveip @*tairinfos*) ":5198")))
            (.setGroupName (:groupname @*tairinfos*))
            (.init))]
    client))

(defn record->map
  "Converts a result to a map like

      {:rc [code=0, msg=success]
       :value value
       :version 3}"
  [^Result r]
  (when (.getValue r)
    {:rc      (.toString (.getRc r))
     :value   (.getValue (.getValue r))
     :version (.getVersion (.getValue r))}))

(defrecord TairClient [client namespace key]
  client/Client
  (setup! [this test node]
    (Thread/sleep 10000)
    (let [client (connect)]
      (assoc this :client client)))


  (invoke! [this test op]
    (timeout 5000 (assoc op :type :info, :error :timeout)
             (case (:f op)
               :read (assoc op
                       :type :ok,
                       :value (-> client (.get namespace key) record->map :value))
               :write (do (-> client (.put namespace key (:value op)))
                          (assoc op :type :ok))
               :add (do (-> client (.incr namespace key (:value op) 0 0))
                        (assoc op :type :ok))
               )))

  (teardown! [_ test]))

(defn tair-counter-client
  "manipulate a counter key"
  []
  (TairClient. nil (rand-int 1023) "counter-key"))

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
    )
  )

(defn configure!
  "Uploads configuration files to the given node."
  [node test version]
  (info node "configure Tair")
  (c/su
    (c/exec :echo (parser/render-file "dataserver.conf" @*tairinfos*)
            :> (str "/usr/local/tair-" version "/etc/dataserver.conf"))
    (if (in? (:cs @*tairinfos*) node)
      (do
        (c/exec :echo (parser/render-file "configserver.conf" @*tairinfos*)
                :> (str "/usr/local/tair-" version "/etc/configserver.conf"))
        (c/exec :echo (parser/render-file "group.conf" @*tairinfos*)
                :> (str "/usr/local/tair-" version "/etc/group.conf"))))))

(defn start!
  "Start tair."
  [node test version]
  (info node "starting Tair")
  (c/su
    (c/cd (str "/usr/local/tair-" version)
          (c/exec :bash (str "tair.sh") (str "start_ds"))
          (if (in? (:cs @*tairinfos*) node)
            (c/exec :bash (str "tair.sh") (str "start_cs"))))
    ))

(defn retrieveip
  "retrieve ip and fill in *tairinfos*"
  [node]
  (info node (net/device-ip (:device @*tairinfos*)))
  (let [ip (net/device-ip (:device @*tairinfos*))]
    (if (in? (:cs @*tairinfos*) node)
      (if (= node (nth (:cs @*tairinfos*) 0))
        ((dosync (alter *tairinfos* assoc :masterip ip))
          (if (nil? (:slaveip @*tairinfos*))
            (dosync (alter *tairinfos* assoc :slaveip ip))))
        (dosync (alter *tairinfos* assoc :slaveip ip))
        ))
    (info "add" ip "to iplist")
    (dosync (alter *tairinfos* assoc :iplist (concat (:iplist @*tairinfos*) `(~ip))))
    (info "iplist is" (:iplist @*tairinfos*))))

(defn stop!
  "Stop tair."
  [node version]
  (info node "stop Tair")
  (c/su
    (c/cd (str "/usr/local/tair-" version)
          (c/exec :bash (str "tair.sh") (str "stop_ds"))
          (if (in? (:cs @*tairinfos*) node)
            (c/exec :bash (str "tair.sh") (str "stop_cs")))
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
      ; a trick to retrieveip in teardown
      ; as jepsen will run teardown before setup :)
      (retrieveip node)
      (stop! node version)
      )))

(defn tair-counter-test
  [version]
  (let [
        ; nodes (list :yunkai)
        nodes (list :winterfell :riverrun :theeyrie :casterlyrock :highgarden)
        roles (classify nodes)
        ]
    (init-tair-infos roles)
    (assoc tests/noop-test
      :ssh {:username "root", :password "root", :port 22}
      :nodes nodes
      :os debian/os
      :db (db version)
      :client (tair-counter-client)
      :generator (->>
                   (repeat 100 add)
                   (cons r)
                   gen/mix
                   (gen/delay 1/100)
                   (gen/clients)
                   (gen/time-limit 15)
                   )
      :model (model/cas-register)
      :checker (checker/compose {:counter checker/counter
                                 :perf    (checker/perf)})
      )))

(ns tair.core
  (:require [jepsen.tests :as tests]
            [clojure.tools.logging :refer :all]
            [jepsen
             [core :as jepsen]
             [db :as db]
             [control :as c]
             [tests :as tests]]
            [jepsen.os.debian :as debian]
            [clojure.string :as str]
            [jepsen.control.net :as net]
            [clojure.java.io :as io]
            [selmer.parser :as parser]))

(def ^:dynamic *tairinfos* (ref {}))

(defn tair-noop-test
  [version]
  (assoc tests/noop-test
    :nodes [:winterfell :riverrun :theeyrie :casterlyrock :highgarden]
    :ssh {:username "root", :password "root", :port 22}))

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

(defn install!
  "Installs tair on the given nodes."
  [node version]
  (info node "installing tair" version)
  (when-not (= (str version "-1")
               (debian/installed-version "tair"))
    (debian/install ["libgoogle-perftools4"])
    (c/su
      (debian/uninstall! ["tair"])
      (info node "installing tair" version)
      (c/cd "/tmp"
            (c/exec :wget :-O (str "tair.tgz")
                    (str "https://github.com/lotair/tair/releases/download/jepsen/tair-" version
                         "-debian8.tgz"))
            (c/exec :tar :xvfz "tair.tgz")))
    (c/cd (str "/tmp/tair-" version "-debian8")
          (c/exec :dpkg :-i (c/lit "tair*.deb")))))

(defn in?
  "true if coll contains elm"
  [coll elm]
  (some #(= elm %) coll))

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
                :> (str "/usr/local/tair-" version "/etc/group.conf"))
        ))))

(defn start!
  "Start tair."
  [node test version]
  (info node "starting tair")
  (c/su
    (c/cd (str "/usr/local/tair-" version)
          (c/exec :bash (str "tair.sh") (str "start_ds"))
          (if (in? (:cs @*tairinfos*) node)
            (c/exec :bash (str "tair.sh") (str "start_cs"))))))

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
  (info node "stop tair")
  (c/su
    (c/cd (str "/usr/local/tair-" version)
          (c/exec :bash (str "tair.sh") (str "stop_ds"))
          (if (in? (:cs @*tairinfos*) node)
            (c/exec :bash (str "tair.sh") (str "stop_cs")))
          (c/exec :bash (str "tair.sh") (str "clean"))
          (c/exec :rm :-rf (str "ldbdata"))
          )))

(defn db
  "Tair DB for a particular version."
  [version]
  (reify db/DB
    (setup! [_ test node]
      (info node "setting up Tair")
      (doto node
        ;(install! version)
        (configure! test version)
        (start! test version)
        )
      )

    (teardown! [_ test node]
      (info node "tearing down Tair")
      ; a trick to retrieveip in teardown
      ; as jepsen will run teardown before setup :)
      (retrieveip node)
      (stop! node version)
      )))

(defn init-tair-infos
  "init tair infos"
  [roles]
  (dosync (alter *tairinfos* assoc :ds (:ds roles)))
  (dosync (alter *tairinfos* assoc :cs (:cs roles)))
  (dosync (alter *tairinfos* assoc :device "eth1"))
  (dosync (alter *tairinfos* assoc :masterip nil))
  (dosync (alter *tairinfos* assoc :slaveip nil))
  (dosync (alter *tairinfos* assoc :groupname "group_fenqi"))
  (dosync (alter *tairinfos* assoc :copycnt 1))
  (dosync (alter *tairinfos* assoc :bucketcnt 100))
  (dosync (alter *tairinfos* assoc :storage-engine "ldb"))
  (dosync (alter *tairinfos* assoc :iplist [])))

(defn tair-db-test
  [version]
  (let [
        nodes (list :yunkai)
        roles (classify nodes)
        ]
    (init-tair-infos roles)
    (assoc tests/noop-test
      :ssh {:username "root", :password "root", :port 22}
      :nodes nodes
      :os debian/os
      :db (db version))))

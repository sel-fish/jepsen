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
            [clojure.java.io :as io]))

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
    roles
    )
  )

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
          (c/exec :dpkg :-i (c/lit "tair*.deb")))
    )
  )

(defn in?
  "true if coll contains elm"
  [coll elm]
  (some #(= elm %) coll))

(defn configure!
  "Uploads configuration files to the given node."
  [node test version]
  (info node "configure Tair")
  (c/su
    ; Config file
    (if (in? (:cs @*tairinfos*) node)
      (info node "is cs")
      (info node "is not cs"))
    (c/cd (str "/usr/local/tair-" version "/etc")
          (c/exec :cp :dataserver.conf.default :dataserver.conf)
          (if (in? (:cs @*tairinfos*) node)
            (do
              (c/exec :cp :configserver.conf.default :configserver.conf)
              (c/exec :cp :group.conf.default :group.conf))))
    (info node (net/device-ip (:device @*tairinfos*)))
    (c/exec :echo (-> "dataserver.conf"
                      io/resource
                      slurp
                      (str/replace "eth0" (net/device-ip (:device @*tairinfos*))))
            :> (str "/usr/local/tair-" version "/etc/dataserver.conf"))
    )
  )

;(defn start!
;  "Starts tair."
;  [node test]
;  (info node "starting tair")
;  (c/su
;    (c/exec :service :aerospike :start)
;
;    ; Enable auto-dunning as per
;    ; http://www.aerospike.com/docs/operations/troubleshoot/cluster/
;    ; This doesn't seem to actually do anything but ???
;    (c/exec :asinfo :-v
;            "config-set:context=service;paxos-recovery-policy=auto-dun-master")))

(defn db
  "Tair DB for a particular version."
  [version]
  (reify db/DB
    (setup! [_ test node]
      (info node "setting up Tair")
      (doto node
        ;(install! version)
        (configure! test version)
        ; (start! test)
        )
      )

    (teardown! [_ test node]
      (info node "tearing down Tair"))
    )
  )

(defn tair-db-test
  [version]
  (let [
        nodes (list :yunkai)
        roles (classify nodes)
        device "eth1"
        ]
    (dosync (alter *tairinfos* assoc :ds (:ds roles)))
    (dosync (alter *tairinfos* assoc :cs (:cs roles)))
    (dosync (alter *tairinfos* assoc :device device))
    (assoc tests/noop-test
      :ssh {:username "root", :password "root", :port 22}
      :nodes nodes
      :os debian/os
      :db (db version))
    )
  )

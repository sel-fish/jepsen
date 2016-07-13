(ns tair.core
  (:require [jepsen.tests :as tests]
            [clojure.tools.logging :refer :all]
            [jepsen [db :as db]
             [control :as c]
             [tests :as tests]]
            [jepsen.os.debian :as debian]))

(defn tair-noop-test
  [version]
  (assoc tests/noop-test
    ; :nodes [:winterfell :riverrun :theeyrie :casterlyrock :highgarden]
    :nodes [:winterfell]
    :ssh {:username "root", :password "root", :port 10042}))

(defn db
  "Tair DB for a particular version."
  [version]
  (reify db/DB
    (setup! [_ test node]
      (info node "installing Tair" version))

    (teardown! [_ test node]
      (info node "tearing down Tair"))))

(defn tair-db-test
  [version]
  (assoc tests/noop-test
    :nodes [:winterfell :riverrun :theeyrie :casterlyrock :highgarden]
    :ssh {:username "root", :password "root", :port 10022}
    :os debian/os
    :db (db version)))
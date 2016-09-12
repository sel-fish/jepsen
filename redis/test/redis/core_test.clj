(ns redis.core-test
  (:require [clojure.test :refer :all]
            [redis.core :as redis]
            [jepsen.core :as jepsen]))

(deftest redis-sentinel-setup-test
  (is (:valid? (:results (jepsen/run! (redis/redis-sentinel-setup-test "2.8.21"))))))

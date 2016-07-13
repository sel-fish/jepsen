(ns tair.core-test
  (:require [clojure.test :refer :all]
            [clojure.pprint :refer [pprint]]
            [tair.core :as tair]
            [jepsen [core :as jepsen]
                    [report :as report]]))

(deftest tair-noop-test
  (is (:valid? (:results (jepsen/run! (tair/tair-noop-test "2.6.0"))))))

;(deftest tair-db-test
;  (is (:valid? (:results (jepsen/run! (tair/tair-db-test "2.6.0"))))))

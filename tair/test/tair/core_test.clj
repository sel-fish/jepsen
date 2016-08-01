(ns tair.core-test
  (:require [clojure.test :refer :all]
            [clojure.pprint :refer [pprint]]
            [tair.core :as tair]
            [jepsen [core :as jepsen]
                    [report :as report]]))

(deftest tair-counter-test
  (is (:valid? (:results (jepsen/run! (tair/tair-counter-test "2.6.0"))))))

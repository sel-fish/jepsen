(ns tair.core-test
  (:require [clojure.test :refer :all]
            [clojure.pprint :refer [pprint]]
            [tair.core :as tair]
            [jepsen.core :as jepsen]))

; test incr of Tair
;(deftest tair-counter-test
;  (is (:valid? (:results (jepsen/run! (tair/tair-counter-test "2.6.0"))))))

; test tair ds offline
(deftest tair-ds-offline-test
  (is (:valid? (:results (jepsen/run! (tair/tair-ds-offline-test "2.6.0"))))))
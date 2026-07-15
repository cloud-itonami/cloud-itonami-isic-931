(ns sportsleagueadminops.governor-test
  (:require [clojure.test :refer [deftest testing is]]
            [sportsleagueadminops.governor :as gov]
            [sportsleagueadminops.store :as store]))

(deftest hard-check-1-facility-unverified
  (testing "facility-unverified HARD check blocks unverified facilities"
    (let [st (store/seed-db)
          ;; facility-1 is registered & verified, facility-3 is verified=false
          proposal {:op :schedule-facility-booking
                    :facility-id "facility-3"
                    :effect :propose
                    :confidence 0.8}
          result (gov/check {} {} proposal st)]
      (is (false? (:ok? result)))
      (is (some #(= :facility-unverified (:rule %)) (:violations result)))))

  (testing "clean facility passes the facility-unverified check"
    (let [st (store/seed-db)
          proposal {:op :schedule-facility-booking
                    :facility-id "facility-1"
                    :effect :propose
                    :confidence 0.8}
          result (gov/check {} {} proposal st)]
      (is (empty? (filter #(= :facility-unverified (:rule %)) (:violations result)))))))

(deftest hard-check-2-effect-not-propose
  (testing "effect-not-propose HARD check rejects non-:propose effects"
    (let [st (store/seed-db)
          proposal {:op :schedule-facility-booking
                    :facility-id "facility-1"
                    :effect :commit  ;; BAD: should be :propose
                    :confidence 0.8}
          result (gov/check {} {} proposal st)]
      (is (false? (:ok? result)))
      (is (some #(= :effect-not-propose (:rule %)) (:violations result)))))

  (testing ":propose effect passes the check"
    (let [st (store/seed-db)
          proposal {:op :schedule-facility-booking
                    :facility-id "facility-1"
                    :effect :propose
                    :confidence 0.8}
          result (gov/check {} {} proposal st)]
      (is (empty? (filter #(= :effect-not-propose (:rule %)) (:violations result)))))))

(deftest hard-check-3-scope-exclusion
  (testing "scope-exclusion HARD check blocks coaching decisions"
    (let [st (store/seed-db)
          proposal {:op :schedule-facility-booking
                    :facility-id "facility-1"
                    :effect :propose
                    :summary "coaching recommendation for next week"
                    :confidence 0.8}
          result (gov/check {} {} proposal st)]
      (is (false? (:ok? result)))
      (is (some #(= :scope-excluded (:rule %)) (:violations result)))))

  (testing "scope-exclusion blocks athlete selection mentions"
    (let [st (store/seed-db)
          proposal {:op :schedule-facility-booking
                    :facility-id "facility-1"
                    :effect :propose
                    :summary "please help with athlete selection"
                    :confidence 0.8}
          result (gov/check {} {} proposal st)]
      (is (false? (:ok? result)))
      (is (some #(= :scope-excluded (:rule %)) (:violations result)))))

  (testing "scope-exclusion blocks ops outside allowlist"
    (let [st (store/seed-db)
          proposal {:op :approve-roster
                    :facility-id "facility-1"
                    :effect :propose
                    :confidence 0.8}
          result (gov/check {} {} proposal st)]
      (is (false? (:ok? result)))
      (is (some #(= :op-not-allowed (:rule %)) (:violations result)))))

  (testing "allowed-ops pass scope-exclusion check"
    (let [st (store/seed-db)
          proposal {:op :coordinate-supply-request
                    :facility-id "facility-1"
                    :effect :propose
                    :summary "request office supplies for the facility"
                    :confidence 0.8}
          result (gov/check {} {} proposal st)]
      (is (empty? (filter #(= :scope-excluded (:rule %)) (:violations result)))))))

(deftest flag-safety-concern-escalates
  (testing ":flag-safety-concern always escalates regardless of confidence"
    (let [st (store/seed-db)
          proposal {:op :flag-safety-concern
                    :facility-id "facility-1"
                    :effect :propose
                    :summary "equipment hazard on court 2"
                    :confidence 0.1}  ;; Low confidence
          result (gov/check {} {} proposal st)]
      (is (true? (:ok? result)))  ;; No HARD violations
      (is (true? (:escalate? result)))  ;; But escalates
      (is (>= (:confidence result) 0)))))  ;; And confidence is preserved

(deftest confidence-floor
  (testing "low confidence proposal escalates when within allowed-ops"
    (let [st (store/seed-db)
          proposal {:op :coordinate-supply-request
                    :facility-id "facility-1"
                    :effect :propose
                    :summary "request supplies"
                    :confidence 0.5}  ;; Below confidence-floor of 0.6
          result (gov/check {} {} proposal st)]
      (is (true? (:ok? result)))  ;; No HARD violations
      (is (true? (:escalate? result)))  ;; Escalates due to low confidence
      ))

  (testing "high confidence proposal commits when within allowed-ops"
    (let [st (store/seed-db)
          proposal {:op :coordinate-supply-request
                    :facility-id "facility-1"
                    :effect :propose
                    :summary "request supplies"
                    :confidence 0.9}  ;; Above confidence-floor
          result (gov/check {} {} proposal st)]
      (is (true? (:ok? result)))
      (is (false? (:escalate? result))))))

(ns sportsleagueadminops.operation-test
  "Integration tests for `sportsleagueadminops.operation/build` -- builds
  the REAL compiled `langgraph.graph` StateGraph and runs it end-to-end
  via `langgraph.graph/run*` through commit / hard-hold / phase-hold /
  escalate-approve / escalate-reject routes.

  This namespace is genuinely falsifiable: it would not even COMPILE if
  `operation.cljc` regressed to the old hand-rolled pipeline (no
  `build`/`langgraph.graph` to require), every `is` here would fail if a
  HARD governor violation were ever routed through human approval instead
  of straight to :hold, if the ledger were written before a commit/hold
  actually happened, or if `sportsleagueadminops.phase`'s auto-commit gate
  stopped actually gating :commit."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [sportsleagueadminops.operation :as operation]
            [sportsleagueadminops.store :as store]))

(defn- exec
  ([actor tid request phase-num] (exec actor tid request phase-num :mock))
  ([actor tid request phase-num advisor-mode]
   (g/run* actor
           {:request request :phase phase-num :advisor-mode advisor-mode}
           {:thread-id tid})))

(deftest commit-path-clean-proposal
  (testing "a clean, phase-3, verified-facility booking request auto-commits
            through the real compiled graph, appends to the audit ledger,
            AND to the coordination-log via store/commit-record!"
    (let [s (store/seed-db)
          actor (operation/build s)
          result (exec actor "t-commit"
                       {:facility-id "facility-1"
                        :event-name "Corporate Offsite"
                        :date "2026-08-10"
                        :athlete-count 12}
                       3)
          state (:state result)]
      (is (= :done (:status result)))
      (is (= :commit (:decision state)))
      (let [ledger (store/ledger s)]
        (is (= 1 (count ledger)))
        (is (= :commit (:type (first ledger))))
        (is (= :commit (get-in (first ledger) [:record :decision])))
        (is (= "facility-1" (get-in (first ledger) [:record :facility-id]))))
      (is (= 1 (count (store/coordination-log s)))
          "store/commit-record! (coordination-log write) fires on the graph's :commit node"))))

(deftest hard-hold-path-unverified-facility
  (testing "an unverified facility is a HARD, permanent governor
            violation -- the graph routes straight to :hold (never
            through human approval, regardless of phase) and durably
            records the hold fact. Governor rejection blocks commit."
    (let [s (store/seed-db)
          actor (operation/build s)
          result (exec actor "t-hold-unverified"
                       {:facility-id "facility-3" ;; registered but NOT verified
                        :event-name "Summer League Match"
                        :date "2026-08-01"}
                       3)
          state (:state result)]
      (is (= :done (:status result)))
      (is (= :hold (:decision state)))
      (let [ledger (store/ledger s)]
        (is (= 1 (count ledger)))
        (is (= :hold (:type (first ledger))))
        (is (= :governor-violation (get-in (first ledger) [:record :reason])))
        (is (seq (get-in (first ledger) [:record :violations]))))
      (is (empty? (store/coordination-log s))
          "a held proposal never reaches store/commit-record! -- governor rejection blocks commit"))))

(deftest hard-hold-path-unregistered-facility
  (testing "a facility that doesn't exist in the store at all is also a
            HARD violation, re-derived from the store, never trusted
            from the proposal"
    (let [s (store/seed-db)
          actor (operation/build s)
          result (exec actor "t-hold-unregistered"
                       {:facility-id "no-such-facility"
                        :event-name "E" :date "2026-08-01"}
                       3)]
      (is (= :hold (:decision (:state result))))
      (is (empty? (store/coordination-log s))))))

(deftest hard-hold-path-scope-exclusion
  (testing "a proposal touching athlete-selection/lineup content is a HARD
            scope-exclusion violation, blocked regardless of phase or
            confidence"
    (let [s (store/seed-db)
          actor (operation/build s)
          result (exec actor "t-hold-scope"
                       {:facility-id "facility-1"
                        :test-scenario :athlete-selection}
                       3
                       :test-out-of-scope)
          state (:state result)]
      (is (= :hold (:decision state)))
      (is (some #(= :scope-excluded (:rule %))
                (get-in state [:governor-result :violations])))
      (is (empty? (store/coordination-log s))))))

(deftest phase-hold-path-clean-but-not-eligible
  (testing "a clean, non-escalating proposal that isn't in the current
            phase's auto-commit set is held (not committed, not
            escalated) -- and STILL durably audited, distinguished from a
            governor violation by :reason. `phase/phase-decision` was
            never dead code in this repo (unlike some sibling actors),
            but it was only ever exercised via the fake hand-rolled
            pipeline before this fix -- this proves it holds through the
            REAL compiled graph too."
    (let [s (store/seed-db)
          actor (operation/build s)
          result (exec actor "t-phase-hold"
                       {:facility-id "facility-1"
                        :event-name "Regional Basketball Tournament"
                        :date "2026-08-20"
                        :athlete-count 4}
                       1) ;; phase 1: allowed, but :auto is empty
          state (:state result)]
      (is (= :hold (:decision state)))
      (let [ledger (store/ledger s)]
        (is (= 1 (count ledger)))
        (is (= :not-in-phase-auto-set (get-in (first ledger) [:record :reason])))
        (is (empty? (get-in (first ledger) [:record :violations])))))))

(deftest phase-eligibility-actually-gates-commit
  (testing "the SAME clean proposal that holds at phase 1 auto-commits
            once its op enters the phase's auto-commit set at phase 3 --
            proof the phase table is a real gate through the real graph"
    (let [held-store (store/seed-db)
          held (exec (operation/build held-store) "t-phase-1"
                     {:facility-id "facility-1"
                      :event-name "Regional Basketball Tournament"
                      :date "2026-08-20" :athlete-count 4}
                     1)
          committed-store (store/seed-db)
          committed (exec (operation/build committed-store) "t-phase-3"
                          {:facility-id "facility-1"
                           :event-name "Regional Basketball Tournament"
                           :date "2026-08-20" :athlete-count 4}
                          3)]
      (is (= :hold (:decision (:state held))))
      (is (= :commit (:decision (:state committed)))))))

(deftest escalate-then-approve-commits
  (testing ":flag-safety-concern ALWAYS escalates -- the real graph
            GENUINELY interrupts (checkpointed) at :request-approval; the
            ledger stays empty until a human approves; approval resumes
            the SAME compiled graph and commits via the graph's own
            :request-approval -> :commit edge, durably appending to the
            ledger AND the coordination-log (hold-until-approved)"
    (let [s (store/seed-db)
          actor (operation/build s)
          held (exec actor "t-escalate"
                     {:facility-id "facility-1"
                      :concern-type "equipment-hazard"}
                     3
                     :test-safety-concern)]
      (is (= :interrupted (:status held)))
      (is (= [:request-approval] (:frontier held)))
      (is (empty? (store/ledger s)) "not yet committed -- ledger stays empty until approval")
      (is (empty? (store/coordination-log s)))
      (let [approved (g/run* actor {:approval {:status :approved :by "ops-manager-01"}}
                             {:thread-id "t-escalate" :resume? true})
            approved-state (:state approved)]
        (is (= :done (:status approved)))
        (is (= :commit (:decision approved-state)))
        (let [ledger (store/ledger s)]
          (is (= 1 (count ledger)))
          (is (= :commit (:type (first ledger))))
          (is (= :flag-safety-concern (get-in (first ledger) [:record :proposal :op])))
          (is (= "ops-manager-01" (get-in (first ledger) [:record :approved-by]))))
        (is (= 1 (count (store/coordination-log s))))))))

(deftest escalate-then-reject-holds
  (testing "a human operator rejecting an escalated request routes to
            :hold via the :request-approval node's own decision, and
            durably records the rejection -- never commits
            (hold-until-approved: rejection never commits)"
    (let [s (store/seed-db)
          actor (operation/build s)
          _held (exec actor "t-reject"
                      {:facility-id "facility-1"
                       :concern-type "equipment-hazard"}
                      3
                      :test-safety-concern)
          rejected (g/run* actor {:approval {:status :rejected :by "ops-manager-01"}}
                           {:thread-id "t-reject" :resume? true})
          rejected-state (:state rejected)]
      (is (= :done (:status rejected)))
      (is (= :hold (:decision rejected-state)))
      (let [ledger (store/ledger s)]
        (is (= 1 (count ledger)))
        (is (= :approver-rejected (get-in (first ledger) [:record :reason]))))
      (is (empty? (store/coordination-log s))))))

(deftest booking-hold-at-phase-0-never-escalates
  (testing "a routine, non-safety op at phase 0 is neither escalated nor
            committed -- phase 0 permits no ops at all, so :advise
            substitutes the UNCHANGED :noop fallback (via
            `phase/can-operate?`), which the governor's closed allowlist
            then hard-blocks. No interrupt, straight to :hold."
    (let [s (store/seed-db)
          actor (operation/build s)
          result (exec actor "t-booking-phase-0"
                       {:facility-id "facility-1"
                        :event-name "E" :date "2026-08-01"}
                       0)]
      (is (= :done (:status result)) "no interrupt -- this op never escalates")
      (is (= :hold (:decision (:state result)))))))

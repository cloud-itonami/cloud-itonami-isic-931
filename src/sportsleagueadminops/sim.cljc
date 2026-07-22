(ns sportsleagueadminops.sim
  "Demo driver for the sports facility/league administrative coordination
  actor. Runs a self-contained simulation with demo data through the REAL
  compiled `langgraph.graph` StateGraph
  (`sportsleagueadminops.operation/build`) -- not a hand-rolled pipeline.
  Exercises the governor's three HARD checks, the phase auto-commit gate,
  and the human-in-the-loop escalation/approval `interrupt-before` pause
  + resume."
  (:require [sportsleagueadminops.store :as store]
            [sportsleagueadminops.operation :as operation]
            [sportsleagueadminops.advisor :as advisor]
            [sportsleagueadminops.governor :as governor]
            [langgraph.graph :as g]))

(defn- exec
  ([actor tid request phase-num] (exec actor tid request phase-num :mock))
  ([actor tid request phase-num advisor-mode]
   (g/run* actor
           {:request request :phase phase-num :advisor-mode advisor-mode}
           {:thread-id tid})))

;; ---------------------- demo scenarios ----------------------

(defn scenario-happy-path-phase-1
  "Phase 1: facility booking is allowed but not yet auto-commit-eligible
  -- held for approval through the real graph."
  []
  (let [s (store/seed-db)
        actor (operation/build s)
        request {:facility-id "facility-1"
                 :event-name "Regional Basketball Tournament"
                 :date "2026-08-20"
                 :athlete-count 4}
        result (exec actor "demo-1" request 1)]
    {:scenario "happy-path-phase-1"
     :status (:status result)
     :decision (:decision (:state result))
     :expected-decision :hold
     :pass? (and (= :done (:status result))
                 (= :hold (:decision (:state result))))}))

(defn scenario-happy-path-phase-3
  "Phase 3: the SAME request auto-commits (clean, non-safety op, in the
  phase's auto-commit set) through the real compiled graph."
  []
  (let [s (store/seed-db)
        actor (operation/build s)
        request {:facility-id "facility-1"
                 :event-name "Regional Basketball Tournament"
                 :date "2026-08-20"
                 :athlete-count 4}
        result (exec actor "demo-2" request 3)]
    {:scenario "happy-path-phase-3"
     :status (:status result)
     :decision (:decision (:state result))
     :ledger-entries (count (store/ledger s))
     :expected-decision :commit
     :pass? (= :commit (:decision (:state result)))}))

(defn scenario-unregistered-facility
  "HARD check #1: target facility is unverified -> hold regardless of
  phase, never routed through human approval."
  []
  (let [s (store/seed-db)
        actor (operation/build s)
        request {:facility-id "facility-3" ;; registered but NOT verified
                 :event-name "Summer League Match"
                 :date "2026-08-15"}
        result (exec actor "demo-3" request 3)]
    {:scenario "unregistered-facility-hard-check"
     :status (:status result)
     :decision (:decision (:state result))
     :governor-violations (get-in result [:state :governor-result :violations])
     :expected-decision :hold
     :pass? (= :hold (:decision (:state result)))}))

(defn scenario-effect-not-propose
  "HARD check #2: effect not :propose -> hard violation. Exercises
  `sportsleagueadminops.governor/check` directly (UNCHANGED domain logic)
  against a manually corrupted proposal -- no advisor ever produces a
  non-:propose effect, by design, so this stays a governor-level check
  rather than a full graph run."
  []
  (let [s (store/seed-db)
        adv (advisor/mock-advisor)
        request {:facility-id "facility-1" :event-name "League Match" :date "2026-08-15"}
        proposal (advisor/propose adv request {} s)
        proposal-bad-effect (assoc proposal :effect :commit)
        gov-result (governor/check request {} proposal-bad-effect s)]
    {:scenario "effect-not-propose-hard-check"
     :ok? (:ok? gov-result)
     :violations (:violations gov-result)
     :expected-ok? false
     :pass? (not (:ok? gov-result))}))

(defn scenario-scope-exclusion-athlete-selection
  "HARD check #3: proposal touches athlete-selection/lineup content
  (out of scope) -> hard block, through the real graph via the
  :test-out-of-scope advisor mode."
  []
  (let [s (store/seed-db)
        actor (operation/build s)
        request {:facility-id "facility-1" :test-scenario :athlete-selection}
        result (exec actor "demo-4" request 3 :test-out-of-scope)
        state (:state result)]
    {:scenario "scope-exclusion-athlete-selection"
     :decision (:decision state)
     :violations (get-in state [:governor-result :violations])
     :expected-decision :hold
     :pass? (= :hold (:decision state))}))

(defn scenario-scope-exclusion-competitive-scheduling
  "HARD check #3: proposal touches competitive scheduling/seeding
  (out of scope)"
  []
  (let [s (store/seed-db)
        actor (operation/build s)
        request {:facility-id "facility-1" :test-scenario :competitive-scheduling}
        result (exec actor "demo-5" request 3 :test-out-of-scope)
        state (:state result)]
    {:scenario "scope-exclusion-competitive-scheduling"
     :decision (:decision state)
     :violations (get-in state [:governor-result :violations])
     :expected-decision :hold
     :pass? (= :hold (:decision state))}))

(defn scenario-scope-exclusion-pricing
  "HARD check #3: proposal touches pricing policy (out of scope)"
  []
  (let [s (store/seed-db)
        actor (operation/build s)
        request {:facility-id "facility-1" :test-scenario :pricing}
        result (exec actor "demo-6" request 3 :test-out-of-scope)
        state (:state result)]
    {:scenario "scope-exclusion-pricing"
     :decision (:decision state)
     :violations (get-in state [:governor-result :violations])
     :expected-decision :hold
     :pass? (= :hold (:decision state))}))

(defn scenario-safety-concern-escalates-then-approved
  "`:flag-safety-concern` ALWAYS escalates -- the real graph GENUINELY
  interrupts (checkpointed) at :request-approval; the ledger stays empty
  until a human approves; approval resumes the SAME compiled graph and
  commits via the graph's own :request-approval -> :commit edge."
  []
  (let [s (store/seed-db)
        actor (operation/build s)
        request {:facility-id "facility-1" :concern-type "equipment-hazard"}
        held (exec actor "demo-7" request 3 :test-safety-concern)
        pre-approval-ledger (count (store/ledger s))
        approved (g/run* actor {:approval {:status :approved :by "ops-manager-01"}}
                         {:thread-id "demo-7" :resume? true})]
    {:scenario "safety-concern-escalates-then-approved"
     :status-pre-approval (:status held)
     :frontier-pre-approval (:frontier held)
     :ledger-entries-pre-approval pre-approval-ledger
     :status-post-approval (:status approved)
     :decision-post-approval (:decision (:state approved))
     :ledger-entries-post-approval (count (store/ledger s))
     :pass? (and (= :interrupted (:status held))
                 (zero? pre-approval-ledger)
                 (= :done (:status approved))
                 (= :commit (:decision (:state approved)))
                 (= 1 (count (store/ledger s))))}))

(defn demo
  "Run all scenarios and print results."
  []
  (let [scenarios [scenario-happy-path-phase-1
                   scenario-happy-path-phase-3
                   scenario-unregistered-facility
                   scenario-effect-not-propose
                   scenario-scope-exclusion-athlete-selection
                   scenario-scope-exclusion-competitive-scheduling
                   scenario-scope-exclusion-pricing
                   scenario-safety-concern-escalates-then-approved]
        results (mapv (fn [f] (f)) scenarios)
        passed (filter :pass? results)
        failed (filter #(not (:pass? %)) results)]
    (println (str "\n=== sportsleagueadminops Simulator (real compiled StateGraph) ===\n"
                  "Scenarios: " (count results) "\n"
                  "Passed: " (count passed) "\n"
                  "Failed: " (count failed) "\n"))
    (doseq [r results]
      (println (str "  " (:scenario r) ": " (if (:pass? r) "PASS" "FAIL"))))
    (when (seq failed)
      (println "\nFailed scenarios:")
      (doseq [r failed]
        (println (str "  " (:scenario r)))
        (println (str "    " (dissoc r :scenario :pass?))))))
  nil)

#?(:clj
   (defn -main [& _args]
     (demo)))

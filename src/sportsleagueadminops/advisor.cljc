(ns sportsleagueadminops.advisor
  "SportsLeagueAdminAdvisor -- the LLM-driven proposal generator. This advisor
  generates proposals that the governor will evaluate. The advisor is
  *not* responsible for policy or scope enforcement; those are the
  governor's job.

  The advisor's job is to generate well-reasoned proposals based on the
  request and current state, along with a confidence level. The governor
  will then either approve, hold, escalate, or reject the proposal based on
  its three HARD checks and escalation policy.

  For testing, we provide both a mock advisor (hardcoded responses) and a
  real LLM seam (via langchain.model). The mock is sufficient for testing
  the governor's behavior; the real LLM is for production use.")

(defprotocol Advisor
  (propose [adv request context store] "Generate a proposal"))

;; ----------------------------- MockAdvisor (for testing) -----------------------------

(defrecord MockAdvisor []
  Advisor
  (propose [_ request _context store]
    ;; Happy-path proposal: schedule a facility booking
    {:op :schedule-facility-booking
     :facility-id (:facility-id request)
     :summary (str "Schedule " (:event-name request) " at " (:facility-id request))
     :rationale "Standard facility booking coordination"
     :cites []
     :value {:event-name (:event-name request)
             :date (:date request)
             :athlete-count (get request :athlete-count 1)
             :technical-requirements (get request :technical-requirements [])}
     :effect :propose
     :confidence 0.85}))

(defn mock-advisor [] (->MockAdvisor))

;; ----------------------------- Out-of-Scope Test Advisor
;; This advisor deliberately drafts proposals that touch forbidden scope,
;; so the governor's scope-exclusion scan can be tested end to end.

(defrecord OutOfScopeTestAdvisor []
  Advisor
  (propose [_ request _context _store]
    (case (:test-scenario request)
      :athlete-selection
      {:op :coordinate-team-roster-logistics-proposal
       :facility-id (:facility-id request)
       :summary "Athlete selection and starting lineup decision"
       :rationale "We need to decide athlete selection and the starting lineup for the next match"
       :cites []
       :value {}
       :effect :propose
       :confidence 0.9}

      :competitive-scheduling
      {:op :schedule-facility-booking
       :facility-id (:facility-id request)
       :summary "Competitive scheduling decision about tournament seeding"
       :rationale "Let's decide the competitive seeding and bracket for the tournament"
       :cites []
       :value {}
       :effect :propose
       :confidence 0.9}

      :pricing
      {:op :coordinate-event-logistics
       :facility-id (:facility-id request)
       :summary "Adjust ticket pricing strategy"
       :rationale "We should increase ticket prices to improve revenue"
       :cites []
       :value {}
       :effect :propose
       :confidence 0.9}

      ;; default: return a clean proposal
      {:op :schedule-facility-booking
       :facility-id (:facility-id request)
       :summary "Schedule facility booking"
       :rationale "Standard coordination"
       :cites []
       :value {}
       :effect :propose
       :confidence 0.85})))

(defn out-of-scope-test-advisor [] (->OutOfScopeTestAdvisor))

;; ----------------------------- DefaultAdvisor

(defn advisor
  "Choose an advisor implementation. The mock is sufficient for dev/test.
  For production, use the real LLM seam (future)."
  [mode]
  (case mode
    :mock (mock-advisor)
    :test-out-of-scope (out-of-scope-test-advisor)
    (mock-advisor)))  ;; default to mock

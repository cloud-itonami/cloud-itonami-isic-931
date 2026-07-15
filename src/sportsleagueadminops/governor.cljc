(ns sportsleagueadminops.governor
  "SportsLeagueAdminGovernor -- the independent compliance layer that earns the
  SportsLeagueAdminAdvisor the right to commit. The advisor has no notion of
  whether a facility is actually registered and verified, whether its own
  proposed `:effect` secretly claims a direct actuation instead of a mere
  proposal, or whether it has silently drifted into a permanently
  out-of-scope decision area, so this MUST be a separate system able to
  *reject* a proposal and fall back to HOLD.

  This actor's scope is deliberately narrow -- ADMINISTRATIVE COORDINATION ONLY
  (facility/court booking scheduling, team roster logistics PROPOSAL only,
  equipment/supply coordination, event logistics, safety-concern flagging).
  It NEVER performs or authorizes:
    - coaching decisions or tactical instruction
    - athlete selection, lineup, or eligibility decisions
    - competitive scheduling or seeding
    - disciplinary action or eligibility enforcement
    - ticket pricing or revenue policy decisions
    - safety-authority overrides (injury investigation, license enforcement)

  Three HARD checks, ALL permanent, un-overridable by any human approval:

    1. Facility unverified      -- the target facility record must exist AND
                                   be independently confirmed `:registered?`/
                                   `:verified?` in the store before ANY
                                   proposal for it may commit or even escalate.
                                   Never trusts a proposal's own claim about
                                   the facility -- re-derived from the facility's own
                                   store record, the same 'ground truth, not
                                   self-report' discipline every sibling actor's
                                   governor uses.
    2. Effect not :propose     -- every proposal's `:effect` MUST be
                                   `:propose`. Any other effect value is, by
                                   construction, a claim to directly
                                   actuate/commit outside governance -- HARD
                                   block, not merely low-confidence.
    3. Scope exclusion         -- ANY proposal (regardless of op) whose op,
                                   rationale, summary, citations or draft
                                   value touches coaching/athlete-selection/
                                   competitive-scheduling/disciplinary/
                                   pricing/safety-authority territory is a
                                   HARD, PERMANENT block -- this actor's
                                   charter excludes that territory structurally,
                                   not as a rollout milestone.
                                   Evaluated UNCONDITIONALLY on every proposal.
                                   An op outside the closed five-op allowlist
                                   is the SAME failure mode (an advisor proposing
                                   something it was never authorized to propose)
                                   and is folded into this same check.

  One ESCALATE (SOFT) gate: LLM confidence below the floor, OR the op
  is `:flag-safety-concern` -- ALWAYS escalates to a human, regardless
  of confidence, regardless of how clean the proposal otherwise is.
  `sportsleagueadminops.phase` independently agrees: `:flag-safety-concern` is
  never a member of any phase's `:auto` set either -- two layers, not one."
  (:require [clojure.string :as str]
            [sportsleagueadminops.store :as store]))

(def confidence-floor 0.6)

(def allowed-ops
  "The closed proposal-op allowlist -- an op outside this set is a
  scope violation by construction (see `scope-exclusion-violations`)."
  #{:schedule-facility-booking :coordinate-team-roster-logistics-proposal
    :coordinate-supply-request :coordinate-event-logistics
    :flag-safety-concern})

(def always-escalate-ops
  "Ops that ALWAYS require human sign-off, clean or not."
  #{:flag-safety-concern})

(def scope-excluded-terms
  "Case-insensitive substrings that mark a proposal as touching a
  permanently out-of-scope decision area -- coaching/athlete-selection/
  competitive-scheduling/disciplinary/pricing/safety-authority enforcement.
  Scanned across the proposal's op/summary/rationale/cites/value, never
  trusting the advisor's own framing of its intent."
  ["coach" "coaching" "coach decision" "コーチ判断"
   "athlete selection" "athlete selection" "選手選定" "選手抜擢"
   "lineup" "ラインアップ" "出場選手"
   "competitive" "競技的" "competition seeding" "seeding"
   "disciplinary" "discipline" "disciplinary action" "処分"
   "eligibility" "eligible" "eligibility decision" "出場資格"
   "pricing" "ticket price" "revenue" "料金" "チケット価格" "売上"
   "safety authority" "safety-authority" "safety enforcement"
   "license" "compliance enforcement" "compliance-enforcement"
   "investigat" "complaint" "違反" "通報" "injury investigation"])

;; ----------------------------- checks -----------------------------

(defn- facility-unverified-violations
  "The target facility must exist AND be independently `:registered?`/`:verified?`
  in the store -- never trust the proposal's own `:facility-id` claim without a
  store lookup."
  [{:keys [facility-id]} st]
  (let [f (store/facility st facility-id)]
    (when-not (and f (:registered? f) (:verified? f))
      [{:rule :facility-unverified
        :detail (str facility-id " は未登録または未検証のスポーツ施設 -- いかなる提案も進められない")}])))

(defn- effect-not-propose-violations
  "`:effect` must ALWAYS be `:propose` -- any other value is a claim to
  directly actuate/commit outside governance."
  [proposal]
  (when (not= :propose (:effect proposal))
    [{:rule :effect-not-propose
      :detail (str ":effect は :propose のみ許可されるが " (pr-str (:effect proposal)) " が提案された")}]))

(defn- text-blob
  "Flatten every advisor-authored field on a proposal into one lower-cased
  blob the scope-exclusion scan checks."
  [proposal]
  (str/lower-case (pr-str (select-keys proposal [:op :summary :rationale :cites :value]))))

(defn- scope-exclusion-violations
  "HARD, PERMANENT block: a proposal outside the closed op allowlist, or one
  whose content touches coaching/athlete-selection/competitive-scheduling/
  disciplinary/pricing/safety-authority territory, regardless of confidence
  or how clean every other check is. Evaluated UNCONDITIONALLY on every proposal."
  [proposal]
  (let [op (:op proposal)
        blob (text-blob proposal)]
    (cond
      (not (contains? allowed-ops op))
      [{:rule :op-not-allowed
        :detail (str (pr-str op) " は許可された操作(closed allowlist)に含まれない")}]

      (some #(str/includes? blob %) scope-excluded-terms)
      [{:rule :scope-excluded
        :detail "コーチング/選手選定/競技スケジューリング/処分/料金設定/安全当局の判断領域に触れる提案は永久に禁止"}])))

(defn check
  "Censors a SportsLeagueAdminAdvisor proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal store]
  (let [facility-id (or (:facility-id proposal) (:facility-id request))
        hard (into []
                   (concat (facility-unverified-violations {:facility-id facility-id} store)
                           (effect-not-propose-violations proposal)
                           (scope-exclusion-violations proposal)))
        conf (:confidence proposal 0.0)
        escalate-op (contains? always-escalate-ops (:op proposal))
        low-confidence (< conf confidence-floor)
        ok? (empty? hard)
        escalate? (and ok? (or escalate-op low-confidence))]
    {:ok? ok?
     :violations hard
     :confidence conf
     :escalate? escalate?
     :high-stakes? (not ok?)
     :hard? (not ok?)}))

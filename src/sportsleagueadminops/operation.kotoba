(ns sportsleagueadminops.operation
  "OperationActor -- one sports facility/league administrative-coordination
  request = one supervised actor run, expressed as a REAL compiled
  `langgraph-clj` `StateGraph` (`langgraph.graph/state-graph` +
  `compile-graph`). The advisor (`sportsleagueadminops.advisor/Advisor`)
  is sealed into a single node (`:advise`); its proposal is ALWAYS routed
  through the independent `sportsleagueadminops.governor` (`:govern`) and
  the rollout-phase gate (`:decide`) before anything commits to the SSoT.

  This replaces the previous `run-operation`, which was a plain
  `(-> state (intake)(advise)(govern)(decide)
       ((fn [s] (case (route-from-decide s) ...))))` threading pipeline
  that never required `langgraph.graph` and never touched
  `state-graph`/`add-node`/`compile-graph` at all -- despite this
  namespace's own former docstring calling it \"StateGraph operation\".
  That claim was false; this is the real thing.

  The domain decision logic itself
  (`sportsleagueadminops.phase/phase-decision`, already correctly
  wired to reach :commit for clean, auto-commit-eligible proposals) is
  UNCHANGED here -- unlike some sibling actors in this fleet, this
  repo's `decide` step was never dead code; the ONLY structural bug was
  the missing real graph runtime.

  One structural gap this fix DOES close: the old pipeline's terminal
  `:escalate` state was a dead end -- `escalate` wrote a ledger fact and
  stopped, with no mechanism for a human operator to actually review and
  approve/reject it. Now `:escalate` routes to a genuine
  `:request-approval` node gated by `interrupt-before` -- the compiled
  graph GENUINELY pauses (checkpointed) until a human resumes it with an
  `:approval` decision, then routes onward to the real `:commit` or
  `:hold` node via the graph's own conditional edges. The ledger stays
  empty until that resume, never written eagerly at the moment of
  escalation.

  State machine:
  intake -> advise -> govern -> decide -+-> commit
                                         +-> request-approval -> commit
                                         +-> hold

  Everything the actor depends on is injected, so each is a swap, not a
  rewrite:
    - the Store   (`sportsleagueadminops.store/MemStore`, or any `Store`
                    impl, bound at `build` time)
    - the Advisor (`sportsleagueadminops.advisor/advisor`, selected
                    per-request by `:advisor-mode` -- unchanged)
    - the Phase   (0->3 rollout; passed per-request via `:phase`, not
                    frozen at `build` time)

  One graph run = one facility-coordination request. No unbounded inner
  loop -- each run is auditable and checkpointed. Every commit/hold
  decision fact lands in `sportsleagueadminops.store`'s append-only
  ledger (`store/append-ledger!`) -- that call was already genuinely
  wired (not dead code) in the pre-graph pipeline, and that wiring is
  preserved here, reachable ONLY from the real `:commit` and `:hold`
  terminal nodes (never written speculatively mid-flight, and never
  reachable from `:request-approval` itself while interrupted)."
  (:require [langgraph.graph :as g]
            [langgraph.checkpoint :as cp]
            [sportsleagueadminops.store :as store]
            [sportsleagueadminops.advisor :as advisor]
            [sportsleagueadminops.governor :as governor]
            [sportsleagueadminops.phase :as phase]))

;; ----------------------------- portable time / id ----------------------
;; The old `intake` called `(java.util.UUID/randomUUID)` and
;; `(System/currentTimeMillis)` directly -- unqualified JVM-only Java
;; interop in a `.cljc` file, which would fail to compile under
;; ClojureScript. Fixed via reader-conditionals.

(defn- now-ms []
  #?(:clj (System/currentTimeMillis)
     :cljs (.getTime (js/Date.))))

(defn- new-operation-id []
  (str "op-" #?(:clj (java.util.UUID/randomUUID)
                :cljs (random-uuid))))

;; ----------------------------- State / Transitions ---------------------
;; Node fns each take/return the FULL graph state map. A node may return
;; either a partial update map or the whole state -- `langgraph.graph/
;; apply-updates` folds every returned key through its channel reducer
;; (default: last-write-wins), so returning the whole state is equivalent
;; to returning only the changed keys and simply carries `:request`/
;; `:context`/`:phase`/`:advisor-mode`/`:approval` forward unchanged.

(defn intake
  "Intake node: read the request, seed initial state."
  [{:keys [request] :as state}]
  (assoc state
    :facility-id (:facility-id request)
    :operation-id (new-operation-id)
    :timestamp (now-ms)
    :status :intake))

(defn- advise-node
  "Advise node: ask the advisor to generate a proposal. UNCHANGED domain
  logic (`advisor/advisor` mode selection + `advisor/propose`) -- `store`
  is now supplied by the graph's `build` closure rather than threaded
  through the state map, but the calls themselves are identical to the
  pre-graph pipeline's `advise` step."
  [store {:keys [phase] :as state}]
  (let [adv (advisor/advisor (:advisor-mode state :mock))
        request (:request state)
        context (:context state)
        proposal (if (phase/can-operate? phase (:op (advisor/propose adv request context store)))
                   (advisor/propose adv request context store)
                   {:op :noop :effect :propose :confidence 0.0})]
    (assoc state
      :proposal proposal
      :status :advise)))

(defn- govern-node
  "Govern node: run the proposal through the independent governor's
  three HARD checks. UNCHANGED domain logic (`governor/check`)."
  [store {:keys [proposal context] :as state}]
  (let [request (:request state)
        gov-result (governor/check request context proposal store)]
    (assoc state
      :governor-result gov-result
      :status :govern)))

(defn decide
  "Decide node: phase + governor result -> commit/hold/escalate decision.
  UNCHANGED domain logic -- `phase/phase-decision` already correctly
  reaches :commit for clean, auto-commit-eligible proposals; that was
  never a dead path here. Also derives a human-readable `:reason` for
  the audit ledger, purely from the SAME governor-result/op inputs
  `phase-decision` itself already consumes -- no new domain facts."
  [{:keys [phase proposal governor-result] :as state}]
  (let [op (:op proposal)
        decision (phase/phase-decision phase op governor-result)
        reason (case decision
                 :hold (if (:hard? governor-result)
                         :governor-violation
                         :not-in-phase-auto-set)
                 :escalate (if (contains? governor/always-escalate-ops op)
                             :always-escalate
                             :advisor-escalation)
                 nil)]
    (assoc state
      :decision decision
      :reason reason
      :status :decide)))

;; ----------------------------- Terminal / gate nodes --------------------

(defn- commit-node
  "Terminal node: commit the proposal. Reached either directly from
  :decide (clean + phase-auto-commit-eligible) or via :request-approval
  after a human operator approves an escalated proposal. The ONLY node
  that writes a :commit ledger fact and applies `store/commit-record!`."
  [store {:keys [proposal approval] :as state}]
  (let [record (cond-> {:operation-id (:operation-id state)
                         :timestamp (:timestamp state)
                         :facility-id (:facility-id state)
                         :proposal proposal
                         :decision :commit}
                 approval (assoc :approved-by (:by approval)))]
    (store/append-ledger! store {:type :commit :record record})
    (store/commit-record! store record)
    (assoc state :status :committed :decision-record record)))

(defn- hold-node
  "Terminal node: hold for later review/approval. Reached either directly
  from :decide (governor HARD violation, or clean-but-not-in-phase's
  auto-commit set) or via :request-approval after a human operator
  rejects an escalated proposal. The ONLY node that writes a :hold
  ledger fact -- `store/commit-record!` is never called from here, so a
  held proposal never reaches the coordination-log (governor/approval
  rejection genuinely blocks commit)."
  [store {:keys [proposal governor-result reason] :as state}]
  (let [record {:operation-id (:operation-id state)
                :timestamp (:timestamp state)
                :facility-id (:facility-id state)
                :proposal proposal
                :decision :hold
                :reason reason
                :violations (:violations governor-result)}]
    (store/append-ledger! store {:type :hold :record record})
    (assoc state :status :held :decision-record record)))

(defn request-approval
  "Human-in-the-loop node: gated by `interrupt-before` in `build` below,
  so the compiled graph GENUINELY pauses here (checkpointed) the moment
  :decide routes an escalating proposal to it -- nothing is written to
  the ledger yet, `:status` stays :interrupted on the `run*` result. A
  human operator resumes the SAME thread with an `:approval` map
  (`{:status :approved|:rejected :by \"...\"}`); this node then routes
  onward to the real :commit or :hold node, which alone perform the
  durable ledger writes."
  [state]
  (if (= :approved (get-in state [:approval :status]))
    (assoc state :decision :commit :status :request-approval)
    (assoc state :decision :hold :reason :approver-rejected :status :request-approval)))

;; ----------------------------- Graph / Router ----------------------------

(defn route-from-decide
  "Router after :decide: send to :commit, :request-approval, or :hold."
  [state]
  (case (:decision state)
    :commit :commit
    :escalate :request-approval
    :hold :hold))

(defn route-from-request-approval
  "Router after a human resumes :request-approval."
  [state]
  (case (:decision state)
    :commit :commit
    :hold :hold))

(defn build
  "Compiles an OperationActor graph bound to `store`. opts:
    :checkpointer -- a `langgraph.checkpoint/Checkpointer`
                     (default: in-memory `cp/mem-checkpointer`)

  The compiled graph's input map: `{:request .. :context .. :phase ..
  :advisor-mode ..}` (`:phase`/`:context`/`:advisor-mode` are per-request,
  not frozen at `build` time -- only `store` is closed over here, so
  every node's ledger/commit/facility-lookup writes always land on the
  SAME store instance `build` was called with, never on a mismatched
  store a caller might otherwise pass through the input map)."
  [store & [{:keys [checkpointer]
             :or {checkpointer (cp/mem-checkpointer)}}]]
  (-> (g/state-graph {:channels {}})

      (g/add-node :intake intake)
      (g/add-node :advise (fn [state] (advise-node store state)))
      (g/add-node :govern (fn [state] (govern-node store state)))
      (g/add-node :decide decide)
      (g/add-node :request-approval request-approval)
      (g/add-node :commit (fn [state] (commit-node store state)))
      (g/add-node :hold (fn [state] (hold-node store state)))

      (g/set-entry-point :intake)
      (g/add-edge :intake :advise)
      (g/add-edge :advise :govern)
      (g/add-edge :govern :decide)

      (g/add-conditional-edges :decide route-from-decide)
      (g/add-conditional-edges :request-approval route-from-request-approval)

      (g/set-finish-point :commit)
      (g/set-finish-point :hold)

      (g/compile-graph
       {:checkpointer checkpointer
        :interrupt-before #{:request-approval}})))

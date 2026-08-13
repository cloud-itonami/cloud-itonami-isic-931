(ns sportsleagueadminops.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 for `cloud-itonami-isic-931`: this
  repo had NO demo page and no generator at all. Every row on the page
  is produced by RUNNING this repo's real actor -- the compiled
  `langgraph.graph` StateGraph built by
  `sportsleagueadminops.operation/build` (`:intake -> :advise ->
  :govern -> :decide -+-> :commit / :request-approval -> :commit /
  :hold`), the independent `sportsleagueadminops.governor`, the
  `sportsleagueadminops.phase` rollout gate and the append-only
  `sportsleagueadminops.store` ledger. Nothing on the page is
  hand-typed telemetry: even the op-contract table is derived at render
  time from `governor/allowed-ops`, `governor/always-escalate-ops` and
  `phase/phases`, so it cannot drift away from the code the way a
  hand-written table would.

  All entity ids on the page come from `store/demo-data`
  (`facility-1`..`facility-3`, `booking-1`, `booking-2`). The single
  exception is the deliberately-absent `no-such-facility`, which is the
  id this repo's OWN test suite
  (`operation_test/hard-hold-path-unregistered-facility`) uses to prove
  the governor re-derives facility verification from the store rather
  than trusting the request -- it is labelled as absent on the page.
  Request `:event-name` strings are operator-supplied INPUT, not entity
  ids; the clean scenarios reuse the seeded bookings' own event names,
  and the refused ones carry the out-of-scope wording that is the point
  of the refusal.

  DETERMINISM: `operation/intake` stamps every run with a random
  `:operation-id` (UUID) and a wall-clock `:timestamp`. Neither is ever
  rendered -- `:operation-id` is used only as a join key in memory (it
  is the ONLY unique key between a run and its ledger record; joining
  on facility-id or op would be ambiguous because several scenarios
  share both). The page is therefore byte-identical across reruns.

  Usage: `clojure -M:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [jp-go-dds.skin]
            [langgraph.graph :as g]
            [sportsleagueadminops.advisor :as advisor]
            [sportsleagueadminops.governor :as governor]
            [sportsleagueadminops.operation :as operation]
            [sportsleagueadminops.phase :as phase]
            [sportsleagueadminops.store :as store]))

;; ----------------------------- driving the real actor -----------------------

(defn- exec!
  "One full graph run. Returns the run receipt: the caller-supplied
  labelling plus the REAL `:operation-id`/`:status`/`:decision` the
  compiled graph produced."
  [actor tid {:keys [label request phase advisor-mode]}]
  (let [result (g/run* actor
                       {:request request
                        :phase phase
                        :advisor-mode (or advisor-mode :mock)}
                       {:thread-id tid})
        state (:state result)]
    {:label label
     :thread-id tid
     :phase phase
     :advisor-mode (or advisor-mode :mock)
     :facility-id (:facility-id request)
     :event-name (:event-name request)
     :operation-id (:operation-id state)
     :status (:status result)
     :frontier (:frontier result)
     :decision (:decision state)
     :reason (:reason state)
     :proposal-op (get-in state [:proposal :op])
     :violations (get-in state [:governor-result :violations])}))

(defn- resume!
  "Resume an interrupted (`interrupt-before #{:request-approval}`) run
  with a human decision. Returns the updated receipt."
  [actor receipt approval]
  (let [result (g/run* actor {:approval approval}
                       {:thread-id (:thread-id receipt) :resume? true})
        state (:state result)]
    (assoc receipt
           :paused-at (:frontier receipt)
           :paused-status (:status receipt)
           :approval approval
           :operation-id (or (:operation-id state) (:operation-id receipt))
           :status (:status result)
           :decision (:decision state)
           :reason (:reason state)
           :proposal-op (get-in state [:proposal :op])
           :violations (get-in state [:governor-result :violations]))))

(def ^:private scenarios
  "Every scenario the console shows, in render order. Facility ids are
  seeded (`store/demo-data`) except the deliberately-absent
  `no-such-facility`; the clean event names are the seeded bookings'
  own."
  [{:tid "s01" :label "Seeded league booking, phase 3 (auto-commit set)"
    :phase 3
    :request {:facility-id "facility-1"
              :event-name "Tuesday Night Basketball League"
              :date "2026-08-19"
              :athlete-count 10}}

   {:tid "s02" :label "Seeded recreational booking, phase 3"
    :phase 3
    :request {:facility-id "facility-2"
              :event-name "Recreational Volleyball"
              :date "2026-08-20"
              :athlete-count 12}}

   {:tid "s03" :label "Same request at phase 1 -- allowed, but not auto-eligible"
    :phase 1
    :request {:facility-id "facility-1"
              :event-name "Tuesday Night Basketball League"
              :date "2026-08-19"
              :athlete-count 10}}

   {:tid "s04" :label "Same request at phase 2 -- still not auto-eligible"
    :phase 2
    :request {:facility-id "facility-2"
              :event-name "Recreational Volleyball"
              :date "2026-08-20"
              :athlete-count 12}}

   {:tid "s05" :label "Facility registered but NOT verified"
    :phase 3
    :request {:facility-id "facility-3"
              :event-name "Summer League Match"
              :date "2026-08-15"}}

   {:tid "s06" :label "Facility absent from the directory entirely"
    :phase 3
    :request {:facility-id "no-such-facility"
              :event-name "Tuesday Night Basketball League"
              :date "2026-08-19"}}

   {:tid "s07" :label "Phase 0 (read-only): op falls back to :noop"
    :phase 0
    :request {:facility-id "facility-1"
              :event-name "Tuesday Night Basketball League"
              :date "2026-08-19"}}

   {:tid "s08" :label "Advisor drifts into athlete selection / lineup"
    :phase 3 :advisor-mode :test-out-of-scope
    :request {:facility-id "facility-1" :test-scenario :athlete-selection}}

   {:tid "s09" :label "Advisor drifts into competitive seeding"
    :phase 3 :advisor-mode :test-out-of-scope
    :request {:facility-id "facility-1" :test-scenario :competitive-scheduling}}

   {:tid "s10" :label "Advisor drifts into ticket pricing / revenue"
    :phase 3 :advisor-mode :test-out-of-scope
    :request {:facility-id "facility-2" :test-scenario :pricing}}

   {:tid "s11" :label "Operator asks to book a player-eligibility hearing"
    :phase 3
    :request {:facility-id "facility-1"
              :event-name "Player Eligibility Hearing (registration dispute)"
              :date "2026-08-21"}}

   {:tid "s12" :label "Operator asks to book an anti-doping investigation panel"
    :phase 3
    :request {:facility-id "facility-2"
              :event-name "Anti-Doping Investigation Panel"
              :date "2026-08-22"}}])

(def ^:private escalation-scenarios
  "Safety-concern runs. `:flag-safety-concern` ALWAYS escalates (both
  `governor/always-escalate-ops` and its absence from every phase's
  `:auto` set), so the compiled graph genuinely interrupts before
  `:request-approval` and only a human resume can move it."
  [{:tid "s13" :label "Equipment hazard flagged -- human APPROVES"
    :phase 3 :advisor-mode :test-safety-concern
    :request {:facility-id "facility-1" :concern-type "equipment-hazard"}
    :approval {:status :approved :by "ops-manager-01"}}

   {:tid "s14" :label "Equipment hazard flagged -- human REJECTS"
    :phase 3 :advisor-mode :test-safety-concern
    :request {:facility-id "facility-2" :concern-type "equipment-hazard"}
    :approval {:status :rejected :by "league-safety-officer-02"}}])

(defn run-demo!
  "Runs every scenario against ONE freshly seeded store, so the audit
  ledger the page shows is a single real append-only run log. Returns
  `{:db .. :receipts .. :direct-check ..}`."
  []
  (let [db (store/seed-db)
        actor (operation/build db)
        plain (mapv (fn [{:keys [tid] :as sc}] (exec! actor tid sc)) scenarios)
        ;; The ledger DELTA across the interrupted run (not its absolute
        ;; size -- every scenario shares one store, so the absolute size
        ;; is already non-zero here and would prove nothing) is what
        ;; shows the graph wrote nothing while it was paused waiting for
        ;; a human.
        escalated (mapv (fn [{:keys [tid approval] :as sc}]
                          (let [before (count (store/ledger db))
                                held (exec! actor tid sc)
                                during (count (store/ledger db))]
                            (resume! actor
                                     (assoc held :ledger-writes-while-paused
                                            (- during before))
                                     approval)))
                        escalation-scenarios)
        ;; HARD check #2 (`:effect` must be `:propose`) is unreachable
        ;; through the graph by construction -- no advisor in this repo
        ;; ever emits a non-:propose effect. This repo's own
        ;; `sim/scenario-effect-not-propose` exercises it by calling the
        ;; real `governor/check` directly on a corrupted proposal; the
        ;; console shows the same, labelled as a direct governor call
        ;; rather than a graph run.
        req {:facility-id "facility-1"
             :event-name "Tuesday Night Basketball League"
             :date "2026-08-19"}
        corrupted (assoc (advisor/propose (advisor/mock-advisor) req {} db)
                         :effect :commit)
        direct (governor/check req {} corrupted db)]
    {:db db
     :receipts (into plain escalated)
     :direct-check {:label "Proposal claims direct actuation (:effect :commit)"
                    :facility-id "facility-1"
                    :proposal-op (:op corrupted)
                    :effect (:effect corrupted)
                    :ok? (:ok? direct)
                    :hard? (:hard? direct)
                    :violations (:violations direct)}}))

;; ----------------------------- classification -------------------------------
;; A HARD governor refusal and a phase/rollout gate hold BOTH land as a
;; `:hold` ledger fact. They are NOT the same thing and are never mixed
;; into one count on this page: a governor refusal carries a non-empty
;; `:violations` vector and `:reason :governor-violation`; a phase hold
;; carries `:reason :not-in-phase-auto-set` and NO violations at all; an
;; approver rejection carries `:reason :approver-rejected` and likewise
;; no violations (the governor was clean -- a human said no).

(defn- hold? [fact] (= :hold (:type fact)))
(defn- commit? [fact] (= :commit (:type fact)))

(defn- hard-hold?
  [fact]
  (and (hold? fact) (seq (get-in fact [:record :violations]))))

(defn- phase-hold?
  [fact]
  (and (hold? fact)
       (empty? (get-in fact [:record :violations]))
       (= :not-in-phase-auto-set (get-in fact [:record :reason]))))

(defn- rejection-hold?
  [fact]
  (and (hold? fact)
       (empty? (get-in fact [:record :violations]))
       (= :approver-rejected (get-in fact [:record :reason]))))

(defn- rules-of [fact]
  (mapv :rule (get-in fact [:record :violations])))

(defn- receipt-by-operation-id
  "Join a ledger fact back to the run that produced it. `:operation-id`
  is the only unique key: several scenarios share the same facility-id
  AND the same proposal op, so a [op facility-id] join would silently
  attribute a record to the wrong run."
  [receipts fact]
  (let [oid (get-in fact [:record :operation-id])]
    (first (filter #(= oid (:operation-id %)) receipts))))

;; ----------------------------- approver attribution -------------------------
;; MEASURED, not assumed: scan the committed records themselves for any
;; approver-ish key rather than asserting that this repo's
;; `operation/commit-node` retains one. If a future change drops it, the
;; page says so instead of quietly showing a stale claim.

(defn- approver-keys
  [record]
  (into (sorted-set)
        (filter #(str/includes? (str (name %)) "approv") (keys record))))

(defn- approver-attribution
  "For every run where a human actually decided, report what survived
  into the durable record."
  [db receipts]
  (let [ledger (vec (store/ledger db))]
    (for [r receipts
          :when (:approval r)
          :let [fact (first (filter #(= (:operation-id r)
                                        (get-in % [:record :operation-id]))
                                    ledger))
                record (:record fact)
                ks (approver-keys record)]]
      {:label (:label r)
       :facility-id (:facility-id r)
       :op (:proposal-op r)
       :submitted-by (get-in r [:approval :by])
       :submitted-status (get-in r [:approval :status])
       :fact-type (:type fact)
       :record-keys ks
       :retained (when (seq ks) (get record (first ks)))
       :retained? (boolean (seq ks))})))

;; ----------------------------- html -----------------------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- kw [v] (if (keyword? v) (name v) (str v)))

(defn- code [v] (str "<code>" (esc (kw v)) "</code>"))

(defn- row [& cells]
  (str "        <tr>" (str/join (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- rows [xs] (str/join "\n" xs))

(defn- section [title lead headers body-rows]
  (str "  <section class=\"card\">\n"
       "    <h2>" title "</h2>\n"
       "    <p class=\"muted\">" lead "</p>\n"
       "    <table>\n"
       "      <thead><tr>" (str/join (map #(str "<th>" % "</th>") headers)) "</tr></thead>\n"
       "      <tbody>\n"
       (rows body-rows) "\n"
       "      </tbody>\n"
       "    </table>\n"
       "  </section>\n"))

;; --- facility / booking directory (seed data, read back out of the store) ---

(defn- facility-row [{:keys [facility-id name registered? verified? address court-count]}]
  (row (code facility-id)
       (esc name)
       (esc address)
       (esc court-count)
       (if registered? "<span class=\"ok\">registered</span>"
           "<span class=\"critical\">unregistered</span>")
       (if verified? "<span class=\"ok\">verified</span>"
           "<span class=\"critical\">unverified &middot; blocks every proposal</span>")))

(defn- booking-row [{:keys [booking-id facility-id event-name date status]}]
  (row (code booking-id) (code facility-id) (esc event-name) (esc date)
       (if (= "confirmed" status)
         "<span class=\"ok\">confirmed</span>"
         (str "<span class=\"warn\">" (esc status) "</span>"))))

;; --- holds -----------------------------------------------------------------

(defn- hard-hold-row [receipts fact]
  (let [r (receipt-by-operation-id receipts fact)
        vs (get-in fact [:record :violations])]
    (row (esc (:label r))
         (code (get-in fact [:record :facility-id]))
         (code (get-in fact [:record :proposal :op]))
         (str/join "<br>" (map #(str "<span class=\"critical\">" (esc (kw (:rule %)))
                                     "</span>") vs))
         (str/join "<br>" (map #(esc (:detail %)) vs)))))

(defn- phase-hold-row [receipts fact]
  (let [r (receipt-by-operation-id receipts fact)]
    (row (esc (:label r))
         (code (get-in fact [:record :facility-id]))
         (code (get-in fact [:record :proposal :op]))
         (esc (:phase r))
         (code (get-in fact [:record :reason]))
         (if (empty? (get-in fact [:record :violations]))
           "<span class=\"muted\">none &mdash; the governor was clean</span>"
           "<span class=\"critical\">unexpected</span>"))))

(defn- escalation-row [receipts fact]
  (let [r (receipt-by-operation-id receipts fact)
        writes (:ledger-writes-while-paused r)]
    (row (esc (:label r))
         (code (get-in fact [:record :facility-id]))
         (code (get-in fact [:record :proposal :op]))
         (str (esc (kw (:paused-status r))) " at "
              (str/join ", " (map #(code %) (:paused-at r))))
         (if (zero? writes)
           (str "<span class=\"ok\">" (esc writes) "</span>")
           (str "<span class=\"critical\">" (esc writes) "</span>"))
         (esc (kw (get-in r [:approval :status])))
         (if (commit? fact)
           "<span class=\"ok\">committed</span>"
           (str "<span class=\"warn\">held &middot; "
                (esc (kw (get-in fact [:record :reason]))) "</span>")))))

;; --- op contract, derived from the code (not hand-typed) --------------------

(defn- op-gate-row [op]
  (let [always? (contains? governor/always-escalate-ops op)
        auto-phases (->> (keys phase/phases)
                         sort
                         (filter #(phase/auto-commit? % op))
                         vec)
        allowed-phases (->> (keys phase/phases)
                            sort
                            (filter #(phase/can-operate? % op))
                            vec)]
    (row (code op)
         (if (seq allowed-phases) (esc (str/join ", " allowed-phases))
             "<span class=\"critical\">never</span>")
         (cond
           always? "<span class=\"warn\">ALWAYS human approval &middot; never auto at any phase</span>"
           (seq auto-phases) (str "<span class=\"ok\">auto-commits at phase "
                                  (esc (str/join ", " auto-phases)) " when clean</span>")
           :else "<span class=\"warn\">human approval at every phase</span>"))))

(defn- ledger-row [idx fact]
  (let [rec (:record fact)]
    (row (esc (inc idx))
         (if (commit? fact)
           "<span class=\"ok\">commit</span>"
           "<span class=\"warn\">hold</span>")
         (code (:facility-id rec))
         (code (get-in rec [:proposal :op]))
         (if-let [rsn (:reason rec)] (code rsn) "<span class=\"muted\">&mdash;</span>")
         (if-let [vs (seq (:violations rec))]
           (str/join ", " (map #(esc (kw (:rule %))) vs))
           "<span class=\"muted\">none</span>")
         (if-let [by (:approved-by rec)]
           (esc by)
           "<span class=\"muted\">&mdash;</span>"))))

(defn- approver-row [{:keys [label facility-id op submitted-by submitted-status
                             fact-type record-keys retained retained?]}]
  (row (esc label)
       (code facility-id)
       (code op)
       (esc submitted-by)
       (code submitted-status)
       (code fact-type)
       (if retained?
         (str "<span class=\"ok\">" (esc (str/join ", " (map kw record-keys)))
              " = " (esc retained) "</span>")
         "<span class=\"warn\">no approver key on the record (audit only &mdash; not retained on record)</span>")))

(defn render
  "Renders the whole console from a completed `run-demo!` result. Every
  cell is read back out of the store or out of a real run receipt."
  [{:keys [db receipts direct-check]}]
  (let [ledger (vec (store/ledger db))
        hard (filterv hard-hold? ledger)
        phase-held (filterv phase-hold? ledger)
        rejections (filterv rejection-hold? ledger)
        escalations (filterv (fn [f] (some #(and (:approval %)
                                                 (= (:operation-id %)
                                                    (get-in f [:record :operation-id])))
                                           receipts))
                             ledger)
        committed (vec (store/coordination-log db))
        attribution (approver-attribution db receipts)
        any-retained? (boolean (some :retained? attribution))
        hard-rules (sort (distinct (mapcat rules-of hard)))]
    (str
     "<!DOCTYPE html>\n"
     "<html lang=\"en\"><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">"
     "<title>cloud-itonami-isic-931 &middot; sports league &amp; facility administration</title><style>"
     (jp-go-dds.skin/dds+skin)
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Sports activities &mdash; facility &amp; league administrative coordination (ISIC 931) &mdash; Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample &middot; governor-gated &middot; every row produced by a real actor run</span>\n"
     "</header>\n"
     "<main>\n"

     "  <section class=\"card\">\n"
     "    <h2>What this page is</h2>\n"
     "    <p class=\"muted\">Generated at build time by <code>sportsleagueadminops.render-html</code> (<code>clojure -M:render-html</code>). "
     "It drives the repo's real compiled <code>langgraph</code> StateGraph "
     "(<code>:intake &rarr; :advise &rarr; :govern &rarr; :decide &rarr; :commit / :request-approval / :hold</code>) over the seeded "
     "<code>sportsleagueadminops.store</code> directory, then reads the resulting append-only ledger back out. "
     "Nothing here is hand-typed: the tables below are the run. "
     "Run ids and timestamps are deliberately omitted because they are random per run &mdash; the page is byte-identical across reruns.</p>\n"
     "    <p class=\"muted\"><b>This run:</b> " (esc (count receipts)) " graph runs &middot; "
     (esc (count ledger)) " ledger facts &middot; "
     (esc (count committed)) " committed &middot; "
     "<span class=\"critical\">" (esc (count hard)) " HARD governor refusals</span> ("
     (esc (str/join ", " (map kw hard-rules))) ") &middot; "
     (esc (count phase-held)) " phase-gate holds &middot; "
     (esc (count rejections)) " approver rejections.</p>\n"
     "  </section>\n"

     (section
      "Seeded facility directory"
      (str "Read back from <code>store/all-facilities</code>. The governor re-derives "
           "<code>:registered?</code>/<code>:verified?</code> from THIS record on every single proposal &mdash; "
           "it never trusts the request's own claim about the facility.")
      ["Facility" "Name" "Address" "Courts" "Registration" "Verification"]
      (map facility-row (store/all-facilities db)))

     (section
      "Seeded bookings"
      "Read back from <code>store/all-bookings</code>. The clean scenarios below reuse these bookings' own event names."
      ["Booking" "Facility" "Event" "Date" "Status"]
      (map booking-row (store/all-bookings db)))

     (section
      (str "HARD governor refusals &mdash; " (count hard) " this run")
      (str "Permanent, un-overridable blocks from <code>sportsleagueadminops.governor/check</code>. "
           "No human approval can lift one: the graph routes them straight from <code>:decide</code> to <code>:hold</code>, "
           "never through <code>:request-approval</code>, and <code>store/commit-record!</code> is never called. "
           "Each row below carries the governor's own <code>:violations</code>, which is what distinguishes a refusal "
           "from the phase-gate holds in the next section.")
      ["Scenario" "Facility" "Proposed op" "Rule" "Governor's own detail"]
      (map (partial hard-hold-row receipts) hard))

     (section
      (str "Phase / rollout gate holds &mdash; " (count phase-held) " this run")
      (str "NOT refusals. These proposals passed every governor check; they are held only because the op is not yet in "
           "the current phase's auto-commit set (<code>phase/auto-commit?</code>). "
           "They carry <b>no violations at all</b> &mdash; counting them as governor holds would overstate the refusal rate, "
           "so this page keeps them in their own table.")
      ["Scenario" "Facility" "Proposed op" "Phase" "Reason" "Governor violations"]
      (map (partial phase-hold-row receipts) phase-held))

     (section
      (str "Human-in-the-loop escalations &mdash; " (count escalations) " this run")
      (str "<code>:flag-safety-concern</code> always escalates &mdash; it is in <code>governor/always-escalate-ops</code> "
           "AND absent from every phase's <code>:auto</code> set, two independent layers. "
           "The compiled graph genuinely pauses (<code>interrupt-before #{:request-approval}</code>, checkpointed): "
           "the &quot;ledger writes while paused&quot; column is the measured change in ledger size across the paused run "
           "(0 = nothing was written speculatively before the human decided).")
      ["Scenario" "Facility" "Op" "Paused" "Ledger writes while paused" "Human decision" "Outcome"]
      (map (partial escalation-row receipts) escalations))

     (section
      "Approver attribution (measured, not assumed)"
      (str "Measured at render time by scanning each durable record for an approver-shaped key, "
           "joined to its run on <code>:operation-id</code> (the only unique key &mdash; several scenarios share both "
           "facility and op, so a facility/op join would misattribute). "
           (if any-retained?
             "The commit path DOES retain the approver on the record. "
             "The store does NOT retain the approver on the record. ")
           "Where a decision does not survive into the record it is marked "
           "<i>(audit only &mdash; not retained on record)</i> rather than silently omitted, so this page self-corrects "
           "if the store changes.")
      ["Scenario" "Facility" "Op" "Decided by" "Decision" "Ledger fact" "On the durable record"]
      (map approver-row attribution))

     (section
      "Direct governor check (not reachable through the graph)"
      (str "HARD check #2 &mdash; <code>:effect</code> must be <code>:propose</code> &mdash; cannot be reached through the graph, "
           "because no advisor in this repo ever emits another effect. "
           "So it is exercised the way this repo's own <code>sim</code> does: by calling the real "
           "<code>governor/check</code> on a proposal whose <code>:effect</code> was corrupted after the advisor produced it. "
           "This row is a real governor call, not a graph run &mdash; hence its own table.")
      ["Scenario" "Facility" "Op" "Claimed effect" "Governor ok?" "Rule" "Detail"]
      [(row (esc (:label direct-check))
            (code (:facility-id direct-check))
            (code (:proposal-op direct-check))
            (code (:effect direct-check))
            (if (:ok? direct-check)
              "<span class=\"ok\">ok</span>"
              "<span class=\"critical\">refused</span>")
            (str/join ", " (map #(esc (kw (:rule %))) (:violations direct-check)))
            (str/join "<br>" (map #(esc (:detail %)) (:violations direct-check))))])

     (section
      "Op contract (derived from the code at render time)"
      (str "Built from <code>governor/allowed-ops</code>, <code>governor/always-escalate-ops</code> and "
           "<code>phase/phases</code> when this page was generated &mdash; not a hand-maintained table, so it cannot drift "
           "away from the governor. Anything outside this closed allowlist is a "
           "<code>:op-not-allowed</code> HARD refusal by construction.")
      ["Op" "Allowed in phases" "Commit gate"]
      (map op-gate-row (sort-by name governor/allowed-ops)))

     (section
      (str "Committed coordination log &mdash; " (count committed) " records")
      (str "<code>store/coordination-log</code>: the proposals that actually landed. "
           "A held proposal never reaches <code>store/commit-record!</code>, so a governor refusal or an approver "
           "rejection can never appear here.")
      ["#" "Facility" "Op" "Decision" "Approved by"]
      (map-indexed
       (fn [i rec]
         (row (esc (inc i))
              (code (:facility-id rec))
              (code (get-in rec [:proposal :op]))
              (str "<span class=\"ok\">" (esc (kw (:decision rec))) "</span>")
              (if-let [by (:approved-by rec)]
                (esc by)
                "<span class=\"muted\">&mdash; (auto-committed, no human in the loop)</span>")))
       committed))

     (section
      (str "Audit ledger &mdash; " (count ledger) " append-only facts (this run)")
      (str "<code>store/ledger</code> in append order. Every commit and every hold this run produced, "
           "written only from the graph's terminal <code>:commit</code>/<code>:hold</code> nodes.")
      ["#" "Fact" "Facility" "Op" "Reason" "Governor violations" "Approved by"]
      (map-indexed ledger-row ledger))

     "</main>\n</body></html>\n")))

;; ----------------------------- build-time invariant -------------------------

(defn assert-hard-holds!
  "Build-time invariant, NOT a convention: a console that shows no HARD
  governor refusal is not evidence that the governor works, so refuse to
  write the file at all rather than publish a page that only shows
  happy paths. Phase-gate holds and approver rejections deliberately do
  NOT satisfy this -- both carry empty `:violations`, so a naive
  hold-count would pass while the governor was never actually exercised."
  [db]
  (let [ledger (vec (store/ledger db))
        hard (filterv hard-hold? ledger)
        rules (set (mapcat rules-of hard))
        commits (filterv commit? ledger)]
    (when (empty? hard)
      (throw (ex-info (str "refusing to write the console: the run produced ZERO HARD governor holds "
                           "(" (count ledger) " ledger facts, "
                           (count (filterv hold? ledger)) " of them holds, all with empty :violations). "
                           "A page with no real refusal proves nothing.")
                      {:ledger-facts (count ledger)
                       :holds (count (filterv hold? ledger))
                       :hard-holds 0})))
    (when (empty? commits)
      (throw (ex-info "refusing to write the console: the run produced ZERO commits, so the commit path is unproven."
                      {:ledger-facts (count ledger)})))
    {:hard-holds (count hard)
     :hard-rules (vec (sort rules))
     :commits (count commits)}))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db] :as run} (run-demo!)
        {:keys [hard-holds hard-rules commits]} (assert-hard-holds! db)
        html (render run)]
    (spit out html)
    (println "wrote" out
             (str "(" (count (store/ledger db)) " ledger facts, "
                  hard-holds " HARD governor refusals " (pr-str hard-rules) ", "
                  commits " commits, "
                  (count (store/coordination-log db)) " coordination-log records)"))))

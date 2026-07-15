(ns sportsleagueadminops.store
  "SSoT for the ISIC-931 sports facility/league administrative coordination actor,
  behind a `Store` protocol so the backend is a swap, not a rewrite -- the
  same seam every `cloud-itonami-isic-*` actor in this fleet uses.

  This actor coordinates the back-office operations of a sports club, facility,
  or league: facility/court booking scheduling, team roster logistics PROPOSAL
  (administrative only, never coaching/lineup), equipment/supply coordination,
  event logistics coordination, and safety-concern flagging (equipment hazards,
  injury reports).
  It never touches coaching decisions, athlete selection, competitive scheduling,
  disciplinary actions, or safety-authority overrides -- see
  `sportsleagueadminops.governor`'s `scope-exclusion-violations`, a HARD,
  permanent, un-overridable block.

  `MemStore` -- atom of EDN. The deterministic default for dev/tests/demo
  (no deps). A `facilities` directory keyed by `:facility-id` STRING (never a
  keyword -- consistent keying from the start, avoiding the silent-miss bug).

  A registered/verified facility record must exist before ANY proposal for that
  facility may ever commit or escalate -- `sportsleagueadminops.governor`'s
  `facility-unverified-violations` re-derives this from the facility's own
  `:registered?`/`:verified?` fields, never from proposal self-report, the SAME
  'ground truth, not self-report' discipline every sibling actor's own governor uses.

  The ledger stays append-only: which facility a proposal targeted, which operation,
  on what basis, committed/held/escalated and approved by whom is always a query
  over an immutable log.")

(defprotocol Store
  (facility [s facility-id] "Registered facility record, or nil.
    Facility map: {:facility-id .. :name .. :registered? bool :verified? bool}.")
  (all-facilities [s])
  (booking [s booking-id] "Booking record, or nil.")
  (all-bookings [s])
  (ledger [s] "the append-only immutable decision-fact log")
  (coordination-log [s] "the append-only committed coordination-proposal history")
  (commit-record! [s record] "apply a committed proposal's record to the SSoT")
  (append-ledger! [s fact] "append one immutable decision fact")
  (with-facilities [s facilities] "replace/seed the facility directory")
  (with-bookings [s bookings] "replace/seed the booking directory"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained facility and booking directory covering both the
  happy path and the governor's own hard checks, so the actor + tests run offline."
  []
  {:facilities
   {"facility-1" {:facility-id "facility-1" :name "Central Sports Complex"
                  :registered? true :verified? true
                  :address "123 Main St" :court-count 4}
    "facility-2" {:facility-id "facility-2" :name "Community Sports Center"
                  :registered? true :verified? true
                  :address "456 Park Ave" :court-count 2}
    "facility-3" {:facility-id "facility-3" :name "Regional Training Facility (intake)"
                  :registered? true :verified? false
                  :address "789 Sports Blvd" :court-count 6}}
   :bookings
   {"booking-1" {:booking-id "booking-1" :facility-id "facility-1"
                 :event-name "Tuesday Night Basketball League"
                 :date "2026-08-19" :status "confirmed"}
    "booking-2" {:booking-id "booking-2" :facility-id "facility-2"
                 :event-name "Recreational Volleyball"
                 :date "2026-08-20" :status "tentative"}}})

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (facility [_ facility-id] (get-in @a [:facilities facility-id]))
  (all-facilities [_] (sort-by :facility-id (vals (:facilities @a))))
  (booking [_ booking-id] (get-in @a [:bookings booking-id]))
  (all-bookings [_] (sort-by :booking-id (vals (:bookings @a))))
  (ledger [_] (:ledger @a))
  (coordination-log [_] (:coordination-log @a))
  (commit-record! [_ record]
    (swap! a update :coordination-log conj record)
    record)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-facilities [s facilities] (when (seq facilities) (swap! a assoc :facilities facilities)) s)
  (with-bookings [s bookings] (when (seq bookings) (swap! a assoc :bookings bookings)) s))

(defn seed-db
  "A MemStore seeded with the demo facility/booking directory. The deterministic
  default."
  []
  (->MemStore (atom (assoc (demo-data) :ledger [] :coordination-log []))))

(defn mem-store
  "A MemStore seeded with explicit `facilities` and `bookings` maps. The primary
  test/dev entry point. Both may be empty (an unregistered-everywhere store)."
  [facilities bookings]
  (->MemStore (atom {:facilities (or facilities {})
                     :bookings (or bookings {})
                     :ledger []
                     :coordination-log []})))

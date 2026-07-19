# cloud-itonami-isic-931: Sports Activities Operations Coordination

An LLM/actor framework for sports club, facility, and league back-office administrative coordination.

**ISIC Classification**: Rev.4 Division 93 (Sports activities and amusement and recreation activities), Group 931 (Sports activities)

## What This Does

This actor coordinates the operational infrastructure of sports clubs, facilities, and leagues:

- Facility and court booking scheduling
- Administrative team roster logistics coordination (PROPOSAL only, never coaching/lineup decisions)
- Non-competitive consumables supply coordination (equipment, office supplies, cleaning)
- Event/league logistics coordination (schedule administration, non-competitive logistics)
- Facility and player-safety concern flagging

## What This Does NOT Do

This actor explicitly does **not** handle:

- Coaching decisions or tactical instruction
- Athlete selection, lineup, or eligibility decisions
- Competitive scheduling or seeding
- Disciplinary action or eligibility enforcement
- Ticket pricing or revenue policy decisions
- Safety-authority overrides or mandatory reporting (escalates instead)

## Architecture

**Namespace**: `sportsleagueadminops`

**Modules**:
- `store` — String-keyed facility/booking directory (MemStore, EDN-backed)
- `advisor` — Proposal generation and rationale (LLM seam)
- `governor` — Three HARD checks (facility verification, effect validation, scope exclusion)
- `phase` — Rollout phases 0–3 (read-only → auto-commit with escalation)
- `operation` — `langgraph-clj`-style StateGraph: intake → advise → govern → decide → commit | hold | escalate
- `sim` — Demo driver

**All modules are `.cljc`** (ClojureScript + JVM compatible).

## Governor: Three HARD Checks

1. **Facility unverified**: Target facility/booking must exist AND be `:registered?`/`:verified?` in store.
2. **Effect not :propose**: Any `:effect` other than `:propose` is rejected outright.
3. **Scope exclusion**: Any proposal touching coaching, athlete-selection, competitive-scheduling, disciplinary, pricing, or safety-authority territory is permanently blocked.

These are un-overridable, even with human approval.

## Operations (Closed Allowlist)

- `:schedule-facility-booking`
- `:coordinate-team-roster-logistics-proposal`
- `:coordinate-supply-request`
- `:coordinate-event-logistics`
- `:flag-safety-concern` (always escalates)

## Running

### Tests

```bash
clojure -M:dev -e "(require 'clojure.test 'sportsleagueadminops.governor-test) (clojure.test/run-tests 'sportsleagueadminops.governor-test)"
```

### Linting

```bash
clojure -M:lint
```

### Demo/Simulator

```bash
clojure -M:dev -e "(require 'sportsleagueadminops.sim) (sportsleagueadminops.sim/demo)"
```

This runs `sportsleagueadminops.sim/demo`, which exercises the full operation flow
including all three hard-check failure modes and the escalation rules.

## References

- [ADR-2607153800](https://github.com/com-junkawasaki/root/blob/main/90-docs/adr/2607153800-cloud-itonami-isic-931-sports-league-administration-operations.edn): This decision record
- [ADR-2607121000](https://github.com/com-junkawasaki/root/blob/main/90-docs/adr/2607121000-cloud-itonami-global-isic-isco-reverse-toposort-plan.md): Wave-4 rollout structure and ISIC/ISCO plans
- [ADR-2607152700](https://github.com/com-junkawasaki/root/blob/main/90-docs/adr/2607152700-cloud-itonami-isic-873-eldercare-coordination.md): Sibling actor pattern (ISIC 873, residential care)
- [cloud-itonami](https://github.com/cloud-itonami) organization
- [kotoba-lang/industry registry](https://github.com/kotoba-lang/industry)

## License

AGPL-3.0-or-later. See LICENSE for details.

## Contributing

See CONTRIBUTING.md for guidelines.

## Code of Conduct

See CODE_OF_CONDUCT.md.

## Security

See SECURITY.md for vulnerability reporting.

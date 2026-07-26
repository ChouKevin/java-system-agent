# Roadmap Handoff

**Updated:** 2026-07-26 at `47fab8c`
**Supersedes:** the post-Plan-C handoff written 2026-07-23, whose milestones are all complete.

## Where things stand

The original agent has been retired. `src/main/java/com/java/system/agent/` contains exactly `Application.java` and `runtime/` — the Agent V2 kernel and the flow around it. The root suite is 317 tests, green, with no Docker requirement.

`java-semantic-service` is unaffected by any of this work and remains a standalone project.

## What was completed since the last handoff

| Milestone | Result |
| --- | --- |
| Agent V2 package extraction | Kernel moved out of the legacy `analysis` module into its own `runtime` Modulith module with `allowedDependencies = {}` |
| Runtime kernel restructure | Renamed by lifecycle scope, grouped `domain` by aggregate and `application` by flow stage, documented throughout, extracted five validation seams |
| Agent execution flow | Understanding, scope resolution, composition, and verification added around the unmodified kernel; the rules live in `AnswerAcceptancePolicy` and `RepositoryScopeResolver` |
| Clarification and conversation context | The agent asks when no repository resolves, and remembers a bounded ten-turn thread holding dialogue, coordinates and provenance — never evidence |
| Legacy retirement | Seven modules, ~12,000 lines and their fixture corpus deleted; 27,379 deletions total |

The semantic-service milestones referenced by the previous handoff — Plan C, incoming traversal, JDT LS lifecycle, Lombok/framework parity — all remain complete and untouched.

## The agent does not run yet

This is the central fact for whoever picks this up. Two things are missing, and neither is an oversight:

- **All ten outbound ports have only test fakes.** No production adapter exists for any of them.
- **There is no composition root.** `runtime` is deliberately framework-free, so nothing wires `AnalysisApplicationService` and nothing calls it. The Spring context starts with no beans.

`docs/superpowers/reviews/2026-07-26-agent-v2-current-state.md` is the full assessment.

## Open findings, in the order they block work

**F1 — the goal cannot be supplied by a real caller.** `AnswerQuestionCommand` requires a `Goal` naming `InformationNeedId`s that only the understanding step produces, and understanding runs inside the call. No test catches it because each one scripts the fake and builds the goal from the same object. **A design for this is approved** — `docs/superpowers/specs/2026-07-26-goal-derivation-design.md` — and has not been implemented. It must land before an inbound adapter is written, or that adapter will be written against a contract that cannot be satisfied.

**F2 — there is no composition root.** Adapters need somewhere to live and something to wire them, and `runtime` cannot hold Spring configuration without losing the framework-free property that `ApplicationModularityTests` enforces. **No design exists yet.** This blocks every adapter milestone.

**F3 — event-before-state has never run against real persistence.** `TransitionCommitter` implements the invariant correctly and is tested, but the only implementation behind `AnalysisTransitionPort` is an in-memory map, so durability, ordering, and the `FAILED / TRACE_PERSISTENCE_FAILED` path have never been exercised.

**F4 — three recorded debts.** `AttemptState.withStateRevision(long)` is public with no production caller and bypasses the reducer contract; `AnalysisRunPhase` was deliberately not created because its only consumer is the trace store; run-level progress is recorded nowhere.

**F5 and F6** are minor: attempt identity is generated in two places, and `ApplicationStartupTest` now proves only that Spring Boot starts.

## Suggested order

1. **F1**, which is designed and ready to plan.
2. **F2**, which needs a design first.
3. **The semantic HTTP adapter.** It carries the highest external risk — it is the one place where `java-semantic-service`'s actual behavior may not match what `SemanticQueryPort` assumes about revision binding, partial results, and ambiguity classification. Finding that out early is worth more than finding it out late.
4. **The Spring AI adapters** for the three reasoning ports, exercised with a stub `ChatModel` rather than a live API.
5. **Trace persistence**, which closes F3 and F4 and reintroduces the Docker requirement for Mongo tests.
6. **Slack and REST wiring**, once there is something behind them worth calling.

## Environment note

The tree currently has no Docker-dependent test — both lived in the retired `ai/trace` package. Docker on this machine is not working (`/usr/bin/docker: Input/output error`, the usual symptom of Docker Desktop not running or WSL integration being off). That does not block anything until the trace milestone.

## References

- `AGENTS.md` — repository structure, architecture rules, conventions
- `docs/new-agent-model-draft.md` — the Agent V2 architecture document
- `docs/superpowers/reviews/2026-07-26-agent-v2-current-state.md` — full current-state assessment
- `docs/superpowers/specs/2026-07-26-goal-derivation-design.md` — the approved F1 design
- `.superpowers/sdd/progress.md` — executed-milestone ledger, local only

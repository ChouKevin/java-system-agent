# Repository Guidelines

## Project Structure & Module Organization

This repository holds **two independent Maven projects** that share only versioned HTTP contracts and an opaque `repoId`. There is deliberately no `<modules>` aggregation and no parent POM: without aggregation, introducing a shared library would require a dependency visible in a pom diff. Build them separately.

**Root project — the Agent.** A Spring Boot 4 / Java 21 Spring Modulith application. `src/main/java/com/java/system/agent/` contains exactly `Application.java` and `runtime/`, which is the Agent V2 kernel:

```
runtime/
  domain/       run/ scope/ need/ evidence/ answer/ conversation/
  application/  lifecycle/ state/ planning/ semantic/ goal/ understanding/ answer/
                + BoundedAnalysisLoop and AnalysisApplicationService at the root
  port/         in/ out/
```

`domain` groups by aggregate; its dependencies form a chain, `scope ← evidence ← need ← run`, with `answer → need` and `conversation → evidence, scope`. Nothing may import `answer` or `conversation`. `application` groups by flow stage, and the two orchestrators sit at its root because they are the only classes that span every stage.

**`java-semantic-service/`** is a standalone Java 21 / Spring Boot service that owns repository lifecycle, JDT LS integration, and call-graph construction. It has its own `AGENTS.md`; read that before working in it.

**`knowledge/`** holds hand-authored business documentation (`service-map.md`, `repos/{repoId}/business-map.md`, `summary.md`, `business-groups/*.md`). It is an asset in its own right and is never generated from source. **`repos/`** holds runtime clones and is never committed — anything hand-authored beside a clone is destroyed by the next `git pull`.

## Current State

The agent **does not run yet**, and this is expected rather than broken. The original agent was retired at `47fab8c`, and V2's adapters have not been built:

- All ten outbound ports are satisfied only by test fakes. There is no production implementation of `SemanticQueryPort`, `AnalysisTransitionPort`, `ConversationContextPort`, `RepositoryCatalogPort`, `QuestionUnderstandingPort`, `AnswerCompositionPort`, `ClaimVerificationPort`, `RepositoryRevisionPort`, `AnalysisCancellationPort`, or `AnalysisAttemptIdGenerator`.
- There is no composition root. `runtime` is deliberately framework-free — a grep for `@Component`, `@Service`, `@Configuration`, `@Bean`, and `@Repository` across it returns nothing — so no Spring wiring constructs `AnalysisApplicationService`, and nothing calls it.
- The Spring context therefore starts with no beans.

`docs/superpowers/reviews/2026-07-26-agent-v2-current-state.md` records the open findings and the order they should be addressed.

## Build, Test, and Development Commands

Run from the repository root:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn -f pom.xml clean test
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn -f pom.xml test -Dtest=ApplicationModularityTests
mvn -f java-semantic-service/pom.xml clean test
```

The root suite requires no Docker and no external service. `ApplicationModularityTests` is the only test that exercises the Modulith contract; `RuntimeKernelArchitectureTest` holds the ArchUnit rules for kernel layering.

## Architecture Rules

These are enforced by tests, not convention:

- **`runtime` depends on no other module.** Its `package-info.java` declares `@ApplicationModule(allowedDependencies = {})`, and `ApplicationModularityTests` asserts it has no direct dependencies.
- **`runtime` exposes exactly three named interfaces**: `domain`, `port-in`, `port-out`, via `@NamedInterface(value = "domain", propagate = true)` and the two port packages. `application` and everything else stays module-internal.
- **`domain` classes depend only on the JDK and their own packages**; inbound and outbound ports depend only on the JDK and domain. `RuntimeKernelArchitectureTest` enforces all four rules.
- **`DefaultStateReducer` is the only component that transitions an `AttemptState`.** Its `reduce` is an exhaustive pattern `switch` over a sealed `AnalysisEvent` with no `default`, so a new event type breaks compilation until it is handled, and `next()` is the only place `stateRevision` is incremented. `AttemptState.initial(...)` is the one documented exception, used at attempt preparation. Do not split this class; its size is concentration of invariants, not a smell.
- **The LLM proposes, the runtime disposes.** Every model output passes a deterministic gate before it can affect anything: candidate repositories must exist in the catalog, claims cite runtime-issued evidence handles and an unknown handle counts as no citation, and a verdict missing for a claim means `UNSUPPORTED`. The model never selects a capability, changes state, decides termination, compares revisions, or produces an identifier the runtime did not supply.

## Coding Style & Naming Conventions

Four-space indentation and explicit Java types; **never use `var`**. Prefer records for immutable value objects, and put behavior on the type that owns the data. Avoid raw `== null` / `!= null` — use `Objects`, Spring assertions, or collection/string utilities. Use meaningful domain exceptions, never bare `RuntimeException`. Never inline a package name: use imports, not `new java.util.ArrayList<>()`.

**Javadoc is written in Traditional Chinese with no trailing `。`** — a line break ends a sentence. Every class states what it is and where it sits in the flow.

Class names state their stage and role. The suffix vocabulary is fixed: `…Manager` owns a lifecycle, `…Evaluator` judges whether to stop, `…Planner` chooses the next action, `…Interpreter` translates an external response, `…Reducer` turns an event into state, `…Committer` persists, `…Policy` is a pure rule, `…Validator` asserts invariants.

Types are named by the lifecycle they belong to: `AnalysisRun` and `RunOutcome` are run-scoped; `AttemptState`, `AttemptStatus`, `AttemptBudget`, and `AttemptOutcome` are attempt-scoped.

**Do not create a Java package named `target`.** `.gitignore` carries a bare `target/` for Maven output, which silently ignores a package directory of that name at any depth.

## Testing Guidelines

JUnit 5, AssertJ, and ArchUnit. Name tests `*Test`; every test class's declared name must match its file name.

Test behavior at domain-model boundaries rather than through scripted end-to-end walkthroughs. Where a rule lives in a pure function — `AnswerAcceptancePolicy`, `RepositoryScopeResolver`, `RevisionVector.driftedFrom` — test it there and thoroughly. Orchestrators get only the tests that a pure function cannot express: call counts, argument passing between rounds, and short-circuits. A record whose constructor only calls `Objects.requireNonNull` does not need its own test class.

For adequately covered refactors, keep the relevant tests green rather than inventing a failing test. Use RED-GREEN for new observable behavior and public contract changes.

**Two V2 test fixtures live outside `runtime/`**, at `src/test/java/com/java/system/agent/semantic/adapter/fake/`. Any scoped command written as "everything except `runtime`" will miss or destroy them; they have already been missed twice.

## Commit & Pull Request Guidelines

Conventional Commits: `feat:`, `fix:`, `refactor:`, `test:`, `docs:`, `chore:`. Keep commits small and behavior-focused.

Branch discipline is hook-enforced: `tmp/<task>` for short-lived work, squash-merged into its source; `feature/<name>` for longer work, merged with `--no-ff`. Everything else (`master`, `uat`, …) takes merges only. A `tmp/*` branch is never pushed.

PRs should state the problem and the observable behavior, list the verification commands run, and call out any effect on module boundaries, the Modulith contract, or the ArchUnit rules.

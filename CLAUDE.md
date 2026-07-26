# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Read AGENTS.md first

**[`AGENTS.md`](AGENTS.md) is the authoritative description of this repository** — structure, current state, architecture rules, coding style, testing approach, and commit conventions. It is shared with every agent and every human working here. Read it before touching code, and update it there rather than duplicating anything below.

`java-semantic-service/` has its own `AGENTS.md`; read that before working inside it.

## Skills

The **`java-coding-standard` skill** is the authoritative coding standard for Java in this repository — invoke it before writing any `.java` file here. `AGENTS.md` summarizes the rules it enforces, but the skill is the source.

The **`spec-vault` skill** manages `docs/superpowers/`, which is gitignored and therefore local-only. Specs, plans, and reviews go there and are recorded with `spec-vault` rather than committed to the repository. When you write or edit anything under `docs/superpowers/`, record it.

**Agents without skills** — Codex and similar — cannot read either. When delegating Java work to one, inline the rules into the prompt; `AGENTS.md` exists partly so that such an agent has something complete to read.

## Where the design lives

`docs/new-agent-model-draft.md` is the Agent V2 architecture document. It records the decisions and their reasoning, and its section numbers are cited throughout the specs.

`docs/superpowers/` holds the working history: `specs/` for approved designs, `plans/` for implementation plans, `reviews/` for assessments. `docs/superpowers/reviews/2026-07-26-agent-v2-current-state.md` is the current state of play and lists the open findings in the order they should be addressed.

`.superpowers/sdd/progress.md` is a durable ledger of executed milestones, including the defects found and the adjudications made. It survives context compaction; trust it and `git log` over recollection.

## Things that have bitten before

These are recorded because each one cost real work:

- **Two V2 test fixtures live outside `runtime/`**, at `src/test/java/com/java/system/agent/semantic/adapter/fake/`. Scoped commands written as "everything except `runtime`" have missed them twice. Enumerate explicitly.
- **A Java package named `target` is silently gitignored.** `.gitignore` line 2 is a bare `target/` for Maven output and matches any directory of that name at any depth. A `package-info.java` was lost this way and only survived via `git add -f`.
- **Word-boundary `sed` protects longer identifiers.** Renaming `Foo` with `\bFoo\b` deliberately leaves `FooTest` alone, so a rename plan must include the class declarations that the boundary protected. Three test classes ended up declaring a name that did not match their file, which compiles because Java only enforces that for `public` classes.
- **Test sources cross module boundaries freely.** A dependency check that greps only `src/main/java` will miss them, and "leaf-first deletion keeps the tree compiling" does not hold for tests.
- **Documentation that states a false invariant is a defect.** Several Javadoc claims in this repository have been withdrawn after review disproved them. Verify a claim against the code before writing it, and if the code contradicts a brief, report rather than document the brief.

## Deferred decisions

Spring Modulith `2.x`, Spring AI `2.0.0`, and springdoc `3.x` were once listed as deliberately deferred major upgrades. That note is obsolete — all three landed together with Spring Boot `4.1.0` in the merge at `74c17c5`. **Still deferred:** JGit `7.x` (the tree carries no JGit today) and Tyrus `2.x`, which moves off the `javax.websocket` line.

Check the resolved version in `pom.xml` before trusting any statement about a library here, including this one.

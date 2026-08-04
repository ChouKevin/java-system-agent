# Java Code Intelligence Extraction Handoff

## Purpose

Move `java-semantic-service/` from `java-system-agent` into its canonical repository while
preserving service history:

```text
git@github.com:ChouKevin/java-code-intelligence.git
```

The extraction is a source-ownership cutover, not a compatibility period. After verification, the
service source exists only in `java-code-intelligence`; `java-system-agent` retains the HTTP client,
opaque `repoId`, revision-pinned service contract, deployment configuration, and consumer tests.

## Preconditions

- Install Git 2.36 or newer, Python 3.6 or newer, and `git-filter-repo`
- Ensure the semantic service changes intended for extraction are committed and pushed to `uat`
- Freeze semantic service writes in `java-system-agent` for the extraction window
- Create `java-code-intelligence` without an initial README, license, or unrelated bootstrap commit
- Confirm SSH access to both repositories
- Record the source `uat` commit SHA in the extraction ticket
- Keep tokens, Git credentials, runtime repository clones, JDT LS workspace data, and local HTTP-client environments outside Git
- Decide separately whether any untracked local files are still useful; history extraction includes committed files only

The local-only `java-semantic-service/docs/uat/` directory is not part of the extraction unless it
is deliberately reviewed and committed before the freeze. Do not copy it implicitly.

## Install `git-filter-repo`

The preferred Linux installation uses `pipx`:

```bash
sudo apt update
sudo apt install pipx
pipx ensurepath
pipx install git-filter-repo
git filter-repo --version
```

The tool may also be installed as the single official script in `$PATH`. Do not run history
rewriting in the normal `java-system-agent` working directory.

## Extract the service history

Use a fresh temporary clone so `git-filter-repo` can enforce its fresh-clone safety checks:

```bash
export EXTRACTION_ROOT=/tmp/java-code-intelligence-extract
rm -rf "$EXTRACTION_ROOT"

git clone --no-local git@github.com:ChouKevin/java-system-agent.git "$EXTRACTION_ROOT"
cd "$EXTRACTION_ROOT"
git checkout uat

git filter-repo \
  --path java-semantic-service/ \
  --path-rename java-semantic-service/: \
  --refs uat
```

The resulting `uat` branch has `pom.xml`, `src/`, `docs/`, and `uat/` at repository root. The
rewritten commit IDs differ from the source repository, but the retained commits preserve their
authors, messages, timestamps, and parent relationships within the extracted history.

## Verify the extracted tree and history

Compare the committed source subtree with the rewritten root before pushing:

```bash
git -C /path/to/java-system-agent ls-tree -r uat:java-semantic-service \
  > /tmp/java-semantic-service-source-tree.txt
git -C "$EXTRACTION_ROOT" ls-tree -r uat \
  > /tmp/java-code-intelligence-extracted-tree.txt
diff -u \
  /tmp/java-semantic-service-source-tree.txt \
  /tmp/java-code-intelligence-extracted-tree.txt
```

Verify that service history and repository-root paths are usable:

```bash
cd "$EXTRACTION_ROOT"
test -f pom.xml
test -f README.md
test ! -e java-semantic-service
git log --oneline -- pom.xml
mvn clean test
```

Run the JDT LS integration profile when the extraction includes semantic-engine lifecycle,
workspace, dependency-resolution, or protocol changes:

```bash
JDTLS_HOME=/opt/jdtls mvn -Pjdtls-it test
```

## Publish `uat`, then establish `main`

Confirm that the target repository is empty before the first push:

```bash
git ls-remote git@github.com:ChouKevin/java-code-intelligence.git
```

An empty result is expected. Do not force-push over an unexpected target history. Publish the
verified extraction as `uat`:

```bash
cd "$EXTRACTION_ROOT"
git remote add target git@github.com:ChouKevin/java-code-intelligence.git
git push -u target uat
```

Configure target-repository CI and branch protection, then merge `uat` into `main` through the new
repository's normal review workflow. The first `main` release must pass the ordinary suite, contract
tests, packaging, and deployment smoke checks.

## Normalize the independent repository

Make one follow-up commit in `java-code-intelligence` that only adjusts repository-local concerns:

- Set CI paths and artifact names for a repository-root Maven project
- Confirm `README.md` and `AGENTS.md` commands run from repository root
- Add deployment packaging without importing Agent source or a shared Java library
- Keep `src/main/resources/openapi/semantic-api-v1.yaml` as the versioned HTTP contract
- Keep the HTTP and MCP adapters over the same framework-neutral application services
- Preserve all 17 read-only MCP tools and the HTTP repository lifecycle endpoints
- Preserve opaque `repoId` and required `expectedRevision` on repository-scoped queries

Do not rename public operations merely because the repository name changed.

## Cut over the Agent consumer

The Agent cutover is complete only when all of these checks pass against a deployed
`java-code-intelligence` instance:

- Repository catalog and current-revision lookup succeed through the Agent HTTP adapter
- The five existing read-only planning capabilities execute against the external service
- Revision mismatch remains fail-closed and requires a refreshed revision
- HTTP authentication and monitoring remain sanitized
- MCP `initialize`, `tools/list`, representative success, typed failure, and follow-up calls pass
- The MCP catalog contains exactly 17 tools
- The external service build and deployment are owned by `java-code-intelligence`
- The root Agent suite passes without compiling or packaging semantic service source

The root Agent may continue using HTTP. MCP is a parallel transport and is not a prerequisite for
the source extraction.

## Remove the embedded service

After the external deployment and Agent cutover are accepted, remove the embedded source in one
Agent-repository change:

```bash
git checkout uat
git pull --ff-only
git rm -r java-semantic-service
```

The same change must update root `README.md`, `AGENTS.md`, build commands, knowledge documents, and
any CI paths that still refer to the embedded service. It must not delete the Agent's
`codeintelligence` HTTP adapter, semantic DTO boundary, configuration, or consumer tests.

## Rollback boundary

Before embedded-source deletion, rollback means continuing to use the committed source in
`java-system-agent` while fixing the target repository. After deletion, rollback the Agent cutover
commit if the external service cannot satisfy its contract. Do not resume dual writes or manually
copy fixes between repositories.

## Completion evidence

Record these values in the extraction ticket:

- Source `java-system-agent/uat` SHA
- Extracted `java-code-intelligence/uat` SHA
- First accepted `java-code-intelligence/main` SHA
- Tree comparison result
- Ordinary and JDT LS verification commands run
- Deployment version and smoke-test result
- Agent cutover SHA
- Embedded-source deletion SHA

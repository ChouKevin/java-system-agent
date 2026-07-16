# java-system-agent

A Spring Boot AI agent that bridges **Slack** with **Java services**. When a user @-mentions the bot in Slack, the agent:

1. **Outer LLM** (business analyst) classifies the question and can use `DocumentTools` to locate relevant repositories and business groups.
2. Explicit API questions call `find_api_call_graph`, which performs trie candidate lookup and analyzes only a unique route; business behavior questions use `find_call_graph` after document-based navigation.
3. **Inner LLM** (code translator, sub-agent) translates the call graph into business-language description, with the ability to expand truncated nodes via `CallGraphExpandTools`.
4. A deterministic evidence gate checks that the required code evidence was collected and restricts unsupported conclusions to safe responses.
5. The result flows back to the outer LLM, which streams the answer and route/evidence status to Slack.

## Quick Start

```bash
cp .env.example .env   # Fill in required values
mvn package -DskipTests
mvn spring-boot:run
```

### Docker

```bash
docker compose up -d --build                         # First time, or after Dockerfile/image changes
docker compose restart app                           # Code changed: restart without rebuild
docker compose up -d --no-build --force-recreate app # .env changed: recreate container without rebuild
docker compose logs -f app                           # View real-time console logs
docker compose exec app bash                         # Enter running container
docker compose down                                  # Stop and remove container
```

#### Log Files

Application logs are written to `logs/` and automatically mapped to the host via volume mount.

```bash
# View current log
tail -f logs/app.log

# Archived logs are in logs/archived/
ls logs/archived/
```

## Build & Test

```bash
mvn test                              # Run all tests
mvn test -Dtest=CallGraphTest         # Run single test class
mvn test -Dtest=ApplicationModularityTests  # Verify module boundaries
mvn spring-boot:run -Dspring-boot.run.profiles=uat  # Run with profile
```

## Environment Variables

Place these in a `.env` file at project root (used by `docker-compose.yml`). See `.env.example` for a template.

### Slack

| Variable | Required | Description |
|----------|----------|-------------|
| `SLACK_APP_TOKEN` | No | Socket Mode token (`xapp-...`); when blank, the Socket Mode listener is disabled |
| `SLACK_BOT_TOKEN` | Yes | Bot token (`xoxb-...`) |
| `SLACK_SIGNING_SECRET` | Yes | Request signing secret |

### AI Provider

| Variable | Profile | Required | Description |
|----------|---------|----------|-------------|
| `OPENAI_API_KEY` | dev | Yes | OpenAI API key |
| `OPENAI_BASE_URL` | dev | No | Custom OpenAI-compatible endpoint |
| `OPENAI_MODEL` | dev | No | Model name (default: `gpt-3.5-turbo`) |
| `GOOGLE_GENAI_API_KEY` | uat/pro | Yes | Google Gemini API key |
| `GOOGLE_GENAI_MODEL` | uat/pro | No | Model name (default: `gemini-3.1-flash-lite`) |

### AI Rate Limiting

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `GOOGLE_GENAI_RATE_LIMIT_ENABLED` | No | `true` | Enables synchronous Gemini request and token rate limiting |
| `GOOGLE_GENAI_RPM` | No | `30` | Maximum requests per minute |
| `GOOGLE_GENAI_TPM` | No | `1000000` | Maximum tokens per minute |
| `GOOGLE_GENAI_RPD` | No | `1500` | Maximum requests per day |
| `GOOGLE_GENAI_MAX_WAIT_MILLIS` | No | `300000` | Maximum time in milliseconds a request may wait for rate-limit capacity before failing fast |

### Git Credentials

| Variable | Required | Description |
|----------|----------|-------------|
| `GIT_USERNAME` | Yes | Git username (email) |
| `GIT_TOKEN` | Yes | Personal Access Token (PAT) |

### Repository Config

Each managed repo needs three variables. The naming convention is `REPO_{REPO_NAME_UPPER}_*`:

| Variable Pattern | Required | Description |
|------------------|----------|-------------|
| `REPO_{NAME}_URL` | Yes | Git clone URL |
| `REPO_{NAME}_API_HOST` | No | Service API host (for API lookup) |
| `REPO_{NAME}_DEFAULT_BRANCH` | No | Default branch (default: `main`) |

Repos are registered in `application.yml` under `git.repos`. Example for `test-repo`:

```bash
REPO_TEST_URL=https://github.com/org/test-repo
REPO_TEST_API_HOST=http://localhost:8080
REPO_TEST_DEFAULT_BRANCH=main
```

### API Protection

| Variable | Required | Description |
|----------|----------|-------------|
| `API_WRITE_TOKEN` | For writes | Shared secret for mutating `/git/**` endpoints, sent as the `X-Api-Token` request header |

The following endpoints require a matching `X-Api-Token` header:

- `POST /git/clone-repo/{repo}`
- `POST /git/pull-repo/{repo}`
- `POST /git/checkout-repo/{repo}`

Read-only `GET /git/**` endpoints and non-Git APIs remain open. A missing or invalid header returns `401`; when `API_WRITE_TOKEN` is not configured, all Git write requests return `403` (fail-closed).

```bash
curl -X POST \
  -H "X-Api-Token: ${API_WRITE_TOKEN}" \
  http://localhost:8080/git/pull-repo/test-repo
```

### Runtime

| Variable | Required | Description |
|----------|----------|-------------|
| `SPRING_PROFILES_ACTIVE` | No | Active profile: `dev` (default), `uat`, `pro` |

### Agent Loop Limits

Agent loop time limits are configured in `application.yml`:

| Setting | Default | Description |
|---------|---------|-------------|
| `agent.loop.analyst.max-wall-ms` | `120000` | Maximum wall-clock time for one analyst loop |
| `agent.loop.translator.max-wall-ms` | `60000` | Maximum wall-clock time for one translator loop |
| `agent.loop.overall.max-wall-ms` | `300000` | End-to-end deadline shared across nested analyst and translator loops |

## Profiles

| Profile | AI Model | Logging | Key Env Vars |
|---------|----------|---------|--------------|
| `dev` (default) | OpenAI-compatible | Application and Spring AI `DEBUG` | `OPENAI_API_KEY`, `OPENAI_BASE_URL`, `OPENAI_MODEL` |
| `uat` | Google Gemini | `INFO` | `GOOGLE_GENAI_API_KEY`, `GOOGLE_GENAI_MODEL` |
| `pro` | Google Gemini | `INFO` | `GOOGLE_GENAI_API_KEY`, `GOOGLE_GENAI_MODEL` |

## LLM Tool Chain

The agent uses a two-layer LLM architecture (outer + inner) with five outer tools:

### Outer LLM Tools (DocumentTools + AgentAnalysisTools)

| Tool | Parameters | Reads | Purpose |
|------|-----------|-------|---------|
| `read_service_map` | (none) | `knowledge/service-map.md` | System-wide repo overview, used to identify target repo |
| `read_business_map` | `repoId` | `knowledge/repos/{repoId}/business-map.md` | Business group list for a repo |
| `read_business_group_doc` | `repoId`, `groupName` | `knowledge/repos/{repoId}/business-groups/{groupName}.md` | Detailed entry points and business logic for a group |
| `find_call_graph` | `repoId`, `packageName`, `className`, `methodSignature` | Java source code in `repos/{repoId}/` | Analyzes method call chain, delegates to inner LLM for translation |
| `find_api_call_graph` | `apiPath`, `httpMethod?`, `repoId?` | Canonical API trie and Java source code | Finds API route candidates; a unique route is analyzed and translated, while zero or multiple matches return a safe status and candidates |

### Inner LLM Tool (CallGraphExpandTools)

| Tool | Parameters | Purpose |
|------|-----------|---------|
| `find_call_graph` | `repoId`, `packageName`, `className`, `methodSignature` | Expands truncated (`TRAVERSAL_CUTOFF`) or data access (`DATA_ACCESS`) nodes for deeper analysis |

### Tool Call Flow

```
User @mention in Slack
  -> Outer LLM classifies evidence requirement
       |-- Explicit API question
       |     -> find_api_call_graph(path, method?, repo?)
       |     -> trie candidate lookup
       |          |-- unique route -> call graph -> Inner LLM translator
       |          |-- no route -> NOT_FOUND safe response and suggestions
       |          |-- multiple routes -> AMBIGUOUS candidate response
       |
       |-- Business behavior question
       |     -> read_service_map / read_business_map / read_business_group_doc
       |     -> find_call_graph(repo, pkg, cls, method)
       |     -> call graph -> Inner LLM translator
       |
       |-- Documentation-only question -> document tools are sufficient
       -> deterministic evidence gate -> streams answer and status to Slack thread
```

### Path parameter matching

Canonical path param matching applies to both stored route templates and lookup input:

- Concrete values such as `/orders/42` match a one-segment parameter route.
- `{id}`, `{id:\d+}`, `:id`, `<id>`, and `{{id}}` are normalized to the same one-segment wildcard, so parameter names do not need to match.
- A terminal `{*path}` or `**` is a rest wildcard that matches zero or more remaining segments, such as both `/files` and `/files/a/b`.
- Full URLs are accepted. The scheme and host are removed, query strings and fragments are discarded, repeated slashes are collapsed, and a trailing slash is removed before lookup.
- An omitted `httpMethod` returns candidates for every method on the matched route. Supplying `repoId` narrows the candidate set to that repository.

Exact static segments are preferred over wildcards. Cross-repo route collisions are preserved as separate candidates: Slack returns the candidate methods, canonical routes, and repo IDs instead of silently choosing a repository.

### Evidence policy

Every Slack question is assigned one evidence requirement before the analyst loop runs:

- `API_CODE_REQUIRED` — an explicit API path or full URL must use `find_api_call_graph`. A direct `find_call_graph` result does not satisfy API route evidence.
- `BUSINESS_CODE_REQUIRED` — questions about actual flows, rules, conditions, calculations, decisions, or side effects may use documents for navigation, but must collect code evidence with `find_call_graph` before making a business claim.
- `DOCS_ONLY` — service overviews, business-group discovery, documentation summaries, and usage questions can finish from documents alone.

The evidence outcome determines what Slack may return:

- `RESOLVED`/`VERIFIED` allows the translated business answer. `TRANSLATION_UNVERIFIED` keeps the answer but adds an explicit verification caveat.
- `NOT_FOUND` says the route could not be verified and asks the user to confirm HTTP method, API path, or repo; safe route suggestions may be included.
- `AMBIGUOUS` lists sanitized route candidates and asks the user to narrow the repo or HTTP method.
- `ANALYSIS_FAILED` says the scope was located but actual behavior could not be verified and asks the user to retry later.
- If the loop is cancelled, times out, reaches its turn limit, or otherwise force-finalizes, the terminal policy still applies. Forced finalization cannot bypass code evidence or preserve an unsupported business claim.

Slack tool-call summaries expose only route and evidence status information. They do not expose internal package, class, or method coordinates.

## Business Documentation Structure

Business documents live under `knowledge/`, which is **owned by the agent and hand-authored**. They are never cloned and never travel with the analyzed repository:

```
knowledge/
  service-map.md                       # Top-level: all repos overview
  repos/
    {repoId}/
      business-map.md                  # Business group index
      business-scope.md                # API/job/consumer inventory, optional
      summary.md                       # Business summary, optional but recommended
      business-groups/
        {groupName}.md                 # One file per business group
```

`knowledge/` is deliberately separate from `repos/`. Repos under `repos/{repoId}/` are working clones that `git pull` overwrites, so any hand-authored document kept beside the source tree is destroyed on the next sync. Keeping the documents in `knowledge/` is what makes them durable.

The tradeoff is explicit: because documents no longer travel with the source, **nothing regenerates them and nothing notices when they drift from the code**. Keeping them accurate is a human responsibility.

A committed example is available at `knowledge/repos/test-repo` (documents) alongside `repos/test-repo` (the source the analyzer reads).

## Adding Another Repository

Adding a repository is two separate steps, because source and documents no longer live together:

1. Register the repository so the source is cloned to `repos/{repoId}/` (see `git.repos` in `application.yml` and the `REPO_{NAME}_*` variables in `.env`).
2. **Hand-author `knowledge/repos/{repoId}/` yourself.** Nothing generates it, and nothing is inherited from the analyzed repository:

```
knowledge/
  service-map.md
  repos/
    {repoId}/
      business-map.md
      business-scope.md                # API/job/consumer inventory, optional
      summary.md
      business-groups/
        {groupName}.md
```

Update these files together:

| File | Required content |
|------|------------------|
| `knowledge/service-map.md` | Add the new `repoId`, service purpose, and primary document path. |
| `knowledge/repos/{repoId}/business-map.md` | List every business group and link to `business-groups/{groupName}.md`. |
| `knowledge/repos/{repoId}/business-groups/{groupName}.md` | Describe the business purpose, required input data, behavior, dependencies, and `find_call_graph` parameters. |
| `knowledge/repos/{repoId}/summary.md` | Summarize the service in business language. |

`repoId` and `groupName` are used to build document paths, so both must match `^[a-z0-9][a-z0-9._-]{0,63}$`. Any other value is rejected.

The expected lookup path is `read_service_map -> read_business_map -> read_business_group_doc -> find_call_graph`. Each business group document should list source lookup rows with `repoId`, `packageName`, `className`, and `methodSignature` values that match real Java source under `repos/{repoId}/src/main/java`.

### Key Config: Call Graph Depth

```yaml
# application.yml
entry-point:
  call-graph-depth: 3    # Max traversal depth for call graph analysis
```

When depth is exceeded, nodes are marked as `TRAVERSAL_CUTOFF` and the inner LLM can expand them on demand.

## Chat Memory

Multi-turn conversation memory is enabled per Slack thread using Spring AI's `PromptChatMemoryAdvisor`.

- **Storage:** In-memory LRU (`LruChatMemoryRepository`, max 500 conversations via `agent.memory.max-conversations`) -- data is lost on application restart
- **Window size:** 20 messages per thread (oldest evicted when exceeded)
- **Conversation ID:** Slack `threadTs` (each thread has isolated memory)
- **Future consideration:** Switch to `JdbcChatMemoryRepository` or another persistent implementation if conversation history must survive restarts
- **Debug endpoints (dev/uat profile only):**
  - `GET /debug/chat-memory` -- list all active conversation IDs
  - `GET /debug/chat-memory/{threadTs}` -- view conversation history
  - `DELETE /debug/chat-memory/{threadTs}` -- clear conversation history

## API Docs

Swagger UI: `/swagger-ui/index.html`

## Further Reading

- [`knowledge/service-map.md`](knowledge/service-map.md) -- Example top-level service index
- [`knowledge/repos/test-repo/business-map.md`](knowledge/repos/test-repo/business-map.md) -- Example business group index

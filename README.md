# java-system-agent

A Spring Boot AI agent that bridges **Slack** with **Java services**. When a user @-mentions the bot in Slack, the agent:

1. **Outer LLM** (business analyst) uses `DocumentTools` to read service-map, business-map, and business group documents to locate the relevant repository and entry points.
2. **Outer LLM** calls `find_call_graph`, which triggers static call graph analysis from the selected entry point.
3. **Inner LLM** (code translator, sub-agent) translates the call graph into business-language description, with the ability to expand truncated nodes via `CallGraphExpandTools`.
4. The result flows back to the outer LLM, which streams a consolidated answer to Slack.

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
| `SLACK_APP_TOKEN` | Yes | Socket Mode token (`xapp-...`) |
| `SLACK_BOT_TOKEN` | Yes | Bot token (`xoxb-...`) |
| `SLACK_SIGNING_SECRET` | Yes | Request signing secret |

### AI Provider

| Variable | Profile | Required | Description |
|----------|---------|----------|-------------|
| `OPENAI_API_KEY` | dev | Yes | OpenAI API key |
| `OPENAI_BASE_URL` | dev | No | Custom OpenAI-compatible endpoint |
| `OPENAI_MODEL` | dev | No | Model name (default: `gpt-3.5-turbo`) |
| `GOOGLE_GENAI_API_KEY` | uat/pro | Yes | Google Gemini API key |
| `GOOGLE_GENAI_MODEL` | uat/pro | No | Model name (default: `gemini-3.1-flash-lite-preview` for uat, `gemini-2.5-flash` for pro) |

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

### Runtime

| Variable | Required | Description |
|----------|----------|-------------|
| `SPRING_PROFILES_ACTIVE` | No | Active profile: `dev` (default), `uat`, `pro` |

## Profiles

| Profile | AI Model | Key Env Vars |
|---------|----------|--------------|
| `dev` (default) | OpenAI-compatible | `OPENAI_API_KEY`, `OPENAI_BASE_URL`, `OPENAI_MODEL` |
| `uat` | Google Gemini | `GOOGLE_GENAI_API_KEY`, `GOOGLE_GENAI_MODEL` |
| `pro` | Google Gemini | `GOOGLE_GENAI_API_KEY`, `GOOGLE_GENAI_MODEL` |

## LLM Tool Chain

The agent uses a two-layer LLM architecture (outer + inner) with four tools:

### Outer LLM Tools (DocumentTools + AgentAnalysisTools)

| Tool | Parameters | Reads | Purpose |
|------|-----------|-------|---------|
| `read_service_map` | (none) | `repos/service-map.md` | System-wide repo overview, used to identify target repo |
| `read_business_map` | `repoId` | `repos/{repoId}/docs/business-map.md` | Business group list for a repo |
| `read_business_group_doc` | `repoId`, `groupName` | `repos/{repoId}/docs/business-groups/{groupName}.md` | Detailed entry points and business logic for a group |
| `find_call_graph` | `repoId`, `packageName`, `className`, `methodSignature` | Java source code in `repos/{repoId}/` | Analyzes method call chain, delegates to inner LLM for translation |

### Inner LLM Tool (CallGraphExpandTools)

| Tool | Parameters | Purpose |
|------|-----------|---------|
| `find_call_graph` | `repoId`, `packageName`, `className`, `methodSignature` | Expands truncated (`TRAVERSAL_CUTOFF`) or data access (`DATA_ACCESS`) nodes for deeper analysis |

### Tool Call Flow

```
User @mention in Slack
  -> Outer LLM (business analyst role)
       |-- read_service_map          -> repos/service-map.md
       |-- read_business_map(repo)   -> repos/{repo}/docs/business-map.md
       |-- read_business_group_doc(repo, grp) -> repos/{repo}/docs/business-groups/{grp}.md
       |-- find_call_graph(repo, pkg, cls, method)
       |     -> AnalysisService builds call graph from source code
       |     -> Inner LLM (code translator role)
       |          |-- find_call_graph (expand truncated nodes)
       |          -> Returns business-language description
       -> Streams consolidated answer to Slack thread
```

## Business Documentation Structure

Repos are available under `repos/` at runtime. Each repo must have the following `docs/` structure for the tools to work:

```
repos/
  service-map.md                       # Top-level: all repos overview
  {repoId}/
    docs/
      business-map.md                  # Business group index
      summary.md                       # Business summary, optional but recommended
      business-groups/
        {groupName}.md                 # One file per business group
```

A committed example is available at `repos/test-repo`. It shows the expected source layout, mapper resource location, top-level service map, business map, and per-group documents.

## Adding Another Repository

To add another repository, place the source code under `repos/{repoId}/` and keep the business documents beside that source tree. The minimum useful structure is:

```
repos/
  service-map.md
  {repoId}/
    src/main/java/...
    src/main/resources/...
    docs/
      business-map.md
      summary.md
      business-groups/
        {groupName}.md
```

Update these files together:

| File | Required content |
|------|------------------|
| `repos/service-map.md` | Add the new `repoId`, service purpose, and primary document path. |
| `repos/{repoId}/docs/business-map.md` | List every business group and link to `business-groups/{groupName}.md`. |
| `repos/{repoId}/docs/business-groups/{groupName}.md` | Describe the business purpose, required input data, behavior, dependencies, and `find_call_graph` parameters. |
| `repos/{repoId}/docs/summary.md` | Summarize the service in business language. |

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

- **Storage:** In-memory (`InMemoryChatMemoryRepository`) -- data is lost on application restart
- **Window size:** 20 messages per thread (oldest evicted when exceeded)
- **Conversation ID:** Slack `threadTs` (each thread has isolated memory)
- **Future consideration:** For production workloads, consider switching to `JdbcChatMemoryRepository` or another persistent implementation to avoid memory pressure with high thread volume
- **Debug endpoints (dev/uat profile only):**
  - `GET /debug/chat-memory` -- list all active conversation IDs
  - `GET /debug/chat-memory/{threadTs}` -- view conversation history
  - `DELETE /debug/chat-memory/{threadTs}` -- clear conversation history

## API Docs

Swagger UI: `/swagger-ui/index.html`

## Further Reading

- [`repos/service-map.md`](repos/service-map.md) -- Example top-level service index
- [`repos/test-repo/docs/business-map.md`](repos/test-repo/docs/business-map.md) -- Example business group index

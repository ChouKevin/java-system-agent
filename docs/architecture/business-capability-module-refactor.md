# Agent 業務能力模組化重構計畫

## 狀態

本文件是後續程式重構的執行規格。第一階段只建立清楚的模組邊界與遷移方向；實作時必須維持現有 observable behavior、HTTP contract、資料庫 schema 與 durable lifecycle guarantee。

## 背景

目前 Agent 專案主要依技術責任切分：

```text
runtime
inbox
persistence
capability
codebase
model
slack
worker
```

這個結構能表達 hexagonal architecture 與 adapter boundary，但閱讀單一業務流程時，需要在多個 package 間來回跳轉。例如一個 Slack 問題會跨越：

```text
slack
→ inbox
→ runtime
→ model
→ capability
→ codebase
→ persistence
→ worker
→ slack delivery
```

重構後，第一層 package 應以業務能力作為主要導航；每個業務能力內部再使用 domain、application、port 等架構分層。

另外，capability 未來不只包含 Java code analysis，還會加入：

- log query
- read-only external API query
- future state-changing API execution

因此 `capability` 不應併入 code intelligence，而應成為 framework-neutral 的通用能力平台。

## 架構決策

### 核心業務模組

```text
answering
interaction
codeintelligence
```

### 平台模組

```text
capability
```

### 技術 Adapter 模組

```text
model
persistence
slack
worker
```

業務模組擁有 domain、application 與 port contract；技術模組只實作這些 contract。技術 adapter 可以維持獨立 top-level Spring Modulith module，避免因 package 搬移而模糊 adapter boundary。

## 目標 package 結構

```text
src/main/java/com/java/system/agent/

  answering/
    domain/
      action/
      answer/
      candidate/
      capability/
      conversation/
      evidence/
      handle/
      observation/
      run/
      scope/
    application/
      state/
      validation/
    port/
      in/
      out/

  interaction/
    domain/
      source/
      inbox/
      delivery/
    application/
    port/
      in/
      out/

  capability/
    catalog/
    dispatch/
    spi/

  codeintelligence/
    capability/
    executor/
    semantic/
      dto/

  model/
    action/
    quota/
    verification/
    tool/

  persistence/
    document/
    jdbc/

  slack/
  worker/

  Agent*Configuration.java
```

## 模組責任

### `answering`

`answering` 是「如何回答問題」的業務核心，從目前的 `runtime` 改名而來。

它擁有：

- `AnswerQuestionUseCase`
- Analysis Run / Attempt lifecycle
- `QUERY`、`ANSWER`、`CLARIFY`
- candidate、evidence、citation
- answer validation 與 verification contract
- revision、budget、cancellation validation
- session history contract
- Agent state transition contract
- reducer 與 validated action loop

主要搬移：

```text
runtime.domain       → answering.domain
runtime.application  → answering.application
runtime.port.in      → answering.port.in
runtime.port.out     → answering.port.out
```

必須保留：

```java
@ApplicationModule(allowedDependencies = {})
```

`answering` 不得依賴 Spring、Spring AI、JDBC、Slack、capability implementation、code intelligence implementation 或其他 adapter module。

### `interaction`

`interaction` 是「如何接收、排隊與交付一次互動」的業務能力，從目前的 `inbox` 改名並擴大其語意。

它擁有：

- source event normalization 後的 admission contract
- source replay 與 payload conflict detection
- source thread 到 session 的 identity mapping
- inbox enqueue、claim、retry、capacity defer、failure、recovery
- same-session ordering
- receipt delivery 與 final delivery
- delivery outbox lifecycle
- operations snapshot

主要搬移：

```text
inbox.domain       → interaction.domain
inbox.application  → interaction.application
inbox.port.in      → interaction.port.in
inbox.port.out     → interaction.port.out
```

`interaction` 只可依賴：

```text
answering :: domain
answering :: port-in
```

不得依賴：

```text
answering.application
Spring
JDBC
Slack SDK
persistence implementation
```

### `codeintelligence`

`codeintelligence` 是 Java repository 與 semantic analysis 的 capability provider，從目前的 `codebase` 改名而來。

它擁有：

- Java Semantic Service HTTP adapter
- semantic response schema validation
- semantic error/result mapping
- code artifact digest
- code intelligence capability registrations
- 現有五個 capability executor

現有 capability external name 必須保持不變：

```text
codebase.list-entry-points
codebase.lookup-api-route
codebase.suggest-api-route
codebase.outgoing-call-graph
codebase.incoming-call-graph
```

Java package 可以改名為 `codeintelligence`，但 capability name、version、schema、candidate constraints、registration order 與執行結果不得改變。

### `capability`

`capability` 是 Agent 內部的通用能力平台，不屬於任何單一業務 provider。

未來 provider 應採相同方式接入：

```text
codeintelligence → CapabilityProvider
logquery         → CapabilityProvider
apiexecution     → CapabilityProvider
```

`capability` 不得包含 Java Semantic Service 專屬邏輯，也不得依賴 Spring AI。

## Capability Platform 重構

目前 `CapabilityToolRegistry` 同時處理 policy/catalog、executor registration、Spring AI callback、tool call interpretation 與 input decoding。這些責任必須拆開。

### `CapabilityProvider`

新增 framework-neutral provider SPI：

```java
public interface CapabilityProvider {

    List<CapabilityRegistration> registrations();
}
```

建議位置：

```text
capability.spi.CapabilityProvider
```

規則：

- 回傳 immutable、deterministic ordered registrations
- 不得回傳 null
- registration 不得包含 null
- provider 不直接修改全域 mutable registry
- root composition 必須顯式組裝 provider
- 不使用 component scanning 隱式決定 capability order

### `CapabilityRegistration`

新增 framework-neutral registration type，至少包含：

```text
CapabilityPolicy
CapabilityInputDefinition 或 schema
CapabilityInputDecoder
CapabilityExecutor
```

必須保持以下責任分離：

```text
policy/schema
≠ Spring AI callback
≠ executor
```

### `CapabilityRegistry`

新增：

```text
capability.catalog.CapabilityRegistry
```

責任：

- 接收 ordered providers 或 registrations
- 驗證 capability identity 唯一
- 建立 immutable ordered catalog
- 依 policy 或 identity 查找 registration
- 提供 executor lookup
- startup 時對 duplicate、missing executor、invalid registration fail fast
- 實作 answering 所需的 capability catalog contract
- 不依賴 Spring AI、Slack、JDBC 或 provider-specific adapter

### `CapabilityExecutionDispatcher`

保留 dispatcher，但責任只限於：

- 從 registry 取得 executor
- 執行已由 answering 驗證過的 invocation
- 防止 executor 回傳 null
- 將 registry/contract 問題映射為既有 typed failure
- 保留既有 logging 與 result category

不得包含：

- Spring AI `ToolCallback`
- `AssistantMessage` parsing
- code intelligence 專屬 schema
- Java Semantic Service mapping

### Spring AI Tool Adapter

將 Spring AI integration 搬到 model module，例如：

```text
model.tool.SpringAiCapabilityToolAdapter
```

責任：

- 將 framework-neutral capability definition 轉成 Spring AI tool definition/callback
- 只發布目前 `AgentPromptContext` 已配發的 capability
- 將 tool call 解碼成既有 `QueryAction`
- 保留 opaque capability handle 與 candidate handle validation
- malformed input 仍映射成既有 malformed proposal
- 不直接執行 capability
- 不直接呼叫 Java Semantic Service

完成後，`capability` module 中不得存在 `org.springframework.ai` import。

### Code Intelligence Provider

新增：

```text
codeintelligence.capability.CodeIntelligenceCapabilityProvider
```

由它註冊現有五項 capability，並擁有目前 composition root 中的：

- capability policy 建構
- input schema/decoder definition
- executor association

必須保持以下註冊順序：

```text
1. codebase.list-entry-points
2. codebase.lookup-api-route
3. codebase.suggest-api-route
4. codebase.outgoing-call-graph
5. codebase.incoming-call-graph
```

## External API Capability 規則

唯讀 external API 可作為 `QUERY` capability。

任何會改變外部狀態的 operation，例如 POST、PUT、PATCH、DELETE 或其他 mutation，不得偽裝成 `QUERY`。未來必須建立獨立的 `EXECUTE` contract，至少涵蓋：

- authorization
- approval
- idempotency
- side-effect audit
- timeout/retry semantics
- result reconciliation
- cancellation 與 partial failure policy

本次重構不得實作 `EXECUTE`，也不得新增真實 API 或 log query capability。

## Composition Root

`AgentCapabilityConfiguration` 應只負責 privileged composition：

```text
CodeIntelligenceCapabilityProvider
→ CapabilityRegistry
→ CapabilityExecutionDispatcher
```

未來新增 provider 時，composition diff 必須明確可見，例如：

```java
new CapabilityRegistry(List.of(
        codeIntelligence,
        logQuery,
        apiExecution));
```

不得透過無順序保證的 scanning 自動註冊。

## Modulith Dependency Rules

### `answering`

```text
allowedDependencies = {}
```

公開 named interfaces：

```text
domain
port-in
port-out
```

### `interaction`

允許：

```text
answering :: domain
answering :: port-in
```

公開 named interfaces：

```text
domain
port-in
port-out
```

### `capability`

允許：

```text
answering :: domain
answering :: port-out
```

至少公開：

```text
capability :: spi
capability :: catalog
```

`spi` 供 capability provider 使用，`catalog` 供 model adapter 查詢 framework-neutral definitions。dispatcher internals 不應整包暴露。

### `codeintelligence`

允許：

```text
capability :: spi
answering :: domain
answering :: port-out
```

應避免依賴 capability implementation internals。

### `model`

允許：

```text
answering :: domain
answering :: port-out
capability :: catalog
```

不得直接依賴 code intelligence、persistence、interaction 或 slack。

### `persistence`

更新 imports，使其依賴 answering 與 interaction 的 exposed contracts。

不得依賴：

```text
answering.application
interaction.application
capability internals
model
slack
```

### `slack` 與 `worker`

Slack adapter 只依賴 interaction exposed contracts。

Worker 只依賴 interaction inbound use cases，不直接依賴 JDBC adapter。

## 行為相容要求

重構後必須維持：

1. 模型仍只提出一個 `QUERY`、`ANSWER` 或 `CLARIFY`
2. capability handle 仍由 answering 配發與驗證
3. candidate subset 與順序仍由模型選擇
4. 五個 code capability 名稱、version、schema 與 order 不變
5. capability invocation 仍在 deterministic validation 後才執行
6. dispatcher 不增加 semantic ranking
7. answer verification 行為不變
8. session history 行為不變
9. Agent transition atomicity 與 conflict semantics 不變
10. inbox retry、capacity deferral、startup recovery 與 terminal reconciliation 不變
11. Slack receipt/final delivery ordering 不變
12. PostgreSQL schema 與 migration 不變
13. Java Semantic Service HTTP/OpenAPI contract 不變
14. persistence document version 不變

## 非目標

本次重構不包含：

- 實作 log query capability
- 實作 external API capability
- 新增 `EXECUTE`
- 修改 `ValidatedAgentLoop` 的業務流程
- 合併或刪除其他 outbound ports
- 修改 database migration
- 修改 HTTP/OpenAPI contract
- 合併兩個 Maven project
- 建立 shared Java library 或 parent aggregator

## 建議執行順序

### Phase 1：建立目標 package 與 architecture rules

- 建立 `answering`、`interaction`、`codeintelligence`
- 更新 package-info、NamedInterface 與 ArchUnit rules
- 不改行為

### Phase 2：搬移 `runtime` → `answering`

- 使用 `git mv`
- 更新 imports 與 tests
- 重新命名 `RuntimeKernelArchitectureTest` 為 `AnsweringKernelArchitectureTest`
- 每一批搬移後先恢復編譯

### Phase 3：搬移 `inbox` → `interaction`

- 使用 `git mv`
- 更新 persistence、slack、worker imports
- 重新命名 `InboxModuleArchitectureTest` 為 `InteractionModuleArchitectureTest`

### Phase 4：搬移 `codebase` → `codeintelligence`

- 使用 `git mv`
- 保持 capability external name 不變
- 更新 root configuration 與 tests

### Phase 5：拆分 Capability Platform

- 新增 `CapabilityProvider`
- 新增 `CapabilityRegistration`
- 新增 `CapabilityRegistry`
- 建立 `CodeIntelligenceCapabilityProvider`
- 將 Spring AI integration 搬到 model module
- 保持 schema、registration order 與 behavior 不變

### Phase 6：更新 Modulith/ArchUnit tests

更新：

```text
ApplicationModularityTests
AnsweringKernelArchitectureTest
InteractionModuleArchitectureTest
PersistenceModuleArchitectureTest
CapabilityModuleArchitectureTest
ModelModuleArchitectureTest
```

### Phase 7：更新文件並執行完整驗證

更新：

```text
README.md
AGENTS.md
knowledge/service-map.md
knowledge/repos/java-system-agent/summary.md
knowledge/repos/java-system-agent/business-map.md
knowledge/repos/java-system-agent/business-groups/code-analysis.md
knowledge/repos/java-system-agent/business-groups/slack-agent.md
```

## 測試要求

### Capability Registry

至少涵蓋：

- registration order preserved
- duplicate capability identity rejected
- duplicate provider registration rejected
- null provider rejected
- null registration rejected
- missing executor rejected
- unknown capability lookup returns typed contract failure
- catalog immutable after construction

### Spring AI Tool Adapter

至少涵蓋：

- 只發布 prompt context 已配發的 capability
- tool schema 與現有 schema 相同
- valid tool call 產生相同 `QueryAction`
- malformed JSON 產生既有 malformed result
- unknown tool name 被拒絕
- stale/non-issued capability 被拒絕
- candidate handle 順序不被重排
- adapter 不直接執行 executor

### Code Intelligence Provider

至少涵蓋：

- 恰好註冊五個 capability
- external name 與 version 不變
- registration order 不變
- candidate kinds 與 min/max 不變
- required/optional arguments 不變
- integer range 與 enum values 不變
- 每項 capability 對應正確 executor

## 驗證命令

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn -f pom.xml clean test
```

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn -f pom.xml test \
  -Dtest=ApplicationModularityTests,AnsweringKernelArchitectureTest,InteractionModuleArchitectureTest,PersistenceModuleArchitectureTest,CapabilityModuleArchitectureTest,ModelModuleArchitectureTest
```

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn -f pom.xml -Ppostgres-it verify
```

```bash
mvn -f java-semantic-service/pom.xml clean test
```

若執行環境沒有 Docker，PR 必須明確標示 PostgreSQL integration tests 未執行的原因，不得宣稱已通過。

## 完成條件

- [ ] `runtime` package 已不存在
- [ ] `inbox` package 已不存在
- [ ] `codebase` package 已不存在
- [ ] `answering` 為 framework-free、zero-dependency module
- [ ] `interaction` 只依賴 answering exposed interfaces
- [ ] `capability` 是獨立、framework-neutral platform module
- [ ] capability module 中沒有 `org.springframework.ai` import
- [ ] `CodeIntelligenceCapabilityProvider` 註冊現有五項 capability
- [ ] capability 名稱、schema、順序與結果不變
- [ ] model module 擁有 Spring AI tool definition 與 tool-call interpretation
- [ ] duplicate capability registration 在 startup fail fast
- [ ] Modulith 與 ArchUnit tests 通過
- [ ] root normal test suite 通過
- [ ] Java Semantic Service test suite 通過
- [ ] PostgreSQL integration tests 通過，或明確記錄 Docker 不可用
- [ ] README、AGENTS 與 knowledge 文件完成更新

## Codex 執行限制

- 使用 `git mv` 保留檔案歷史
- 不修改 observable behavior
- 不順便重寫 `ValidatedAgentLoop`
- 不順便合併或刪除其他 outbound ports
- 不新增 log query 或 API capability
- 不新增 `EXECUTE`
- 不改 capability external name
- 不改 persistence document version
- 不改 database migration
- 不改 HTTP/OpenAPI contract
- 不使用 `var`
- 類別名稱與測試檔名必須一致
- 新增或修改的 Javadoc 遵循專案既有繁體中文規範
- 每完成一個 package move 就先恢復編譯，再進行下一個 move

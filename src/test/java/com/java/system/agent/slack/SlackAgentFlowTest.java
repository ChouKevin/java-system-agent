package com.java.system.agent.slack;

import com.java.system.agent.inbox.application.ClaimAdmissionCoordinator;
import com.java.system.agent.inbox.application.DeliveryProcessor;
import com.java.system.agent.inbox.application.DeliveryRetryPolicy;
import com.java.system.agent.inbox.application.DeliveryWorkApplicationService;
import com.java.system.agent.inbox.application.InboxRetryPolicy;
import com.java.system.agent.inbox.application.InboxWorkApplicationService;
import com.java.system.agent.inbox.application.SessionInboxProcessor;
import com.java.system.agent.inbox.application.SourceAcceptanceApplicationService;
import com.java.system.agent.inbox.domain.InboxClaim;
import com.java.system.agent.inbox.domain.InboxFailure;
import com.java.system.agent.inbox.domain.InboxMessage;
import com.java.system.agent.inbox.domain.InboxMessageId;
import com.java.system.agent.inbox.domain.InboxMessageStatus;
import com.java.system.agent.inbox.domain.InboxProcessingOutcome;
import com.java.system.agent.inbox.domain.NormalizedSourceEvent;
import com.java.system.agent.inbox.domain.SourceAcceptance;
import com.java.system.agent.inbox.domain.SourceAcceptanceStatus;
import com.java.system.agent.inbox.domain.SourceAdmission;
import com.java.system.agent.inbox.domain.SourceMessageId;
import com.java.system.agent.inbox.domain.SessionSourceRef;
import com.java.system.agent.inbox.domain.delivery.DeliveryClaim;
import com.java.system.agent.inbox.domain.delivery.DeliveryFailure;
import com.java.system.agent.inbox.domain.delivery.DeliveryId;
import com.java.system.agent.inbox.domain.delivery.DeliveryKind;
import com.java.system.agent.inbox.domain.delivery.DeliveryMessage;
import com.java.system.agent.inbox.domain.delivery.DeliveryStatus;
import com.java.system.agent.inbox.port.out.DeliveryOutboxPort;
import com.java.system.agent.inbox.port.out.SessionInboxPort;
import com.java.system.agent.inbox.port.out.SourceAcceptancePort;
import com.java.system.agent.runtime.adapter.fake.FakeAttemptIdGenerator;
import com.java.system.agent.runtime.adapter.fake.FakeCancellationAdapter;
import com.java.system.agent.runtime.adapter.fake.FakeSessionAdapter;
import com.java.system.agent.runtime.application.AnalysisApplicationService;
import com.java.system.agent.runtime.application.ContextIssuer;
import com.java.system.agent.runtime.application.ValidatedAgentLoop;
import com.java.system.agent.runtime.application.state.AgentStateReducer;
import com.java.system.agent.runtime.application.state.AgentTransitionCommitter;
import com.java.system.agent.runtime.application.validation.AgentActionValidator;
import com.java.system.agent.runtime.application.validation.AnswerDocumentValidator;
import com.java.system.agent.runtime.application.validation.AnswerVerdictValidator;
import com.java.system.agent.runtime.domain.action.ClarifyAction;
import com.java.system.agent.runtime.domain.answer.AnswerVerificationMode;
import com.java.system.agent.runtime.domain.conversation.ConversationTurn;
import com.java.system.agent.runtime.domain.conversation.ParticipantRef;
import com.java.system.agent.runtime.domain.conversation.SessionId;
import com.java.system.agent.runtime.domain.run.AgentBootstrap;
import com.java.system.agent.runtime.domain.run.AgentEvent;
import com.java.system.agent.runtime.domain.run.AgentRunState;
import com.java.system.agent.runtime.domain.run.AgentTransition;
import com.java.system.agent.runtime.domain.run.AnalysisAttemptId;
import com.java.system.agent.runtime.domain.run.AnalysisRunId;
import com.java.system.agent.runtime.domain.run.AttemptBudget;
import com.java.system.agent.runtime.port.out.AgentActionPort;
import com.java.system.agent.runtime.port.out.AgentActionProposal;
import com.java.system.agent.runtime.port.out.AgentPromptContext;
import com.java.system.agent.runtime.port.out.AgentTransitionConflictException;
import com.java.system.agent.runtime.port.out.AgentTransitionPort;
import com.java.system.agent.runtime.port.out.AnswerVerificationResult;
import com.java.system.agent.runtime.port.in.AnswerQuestionResult;
import com.java.system.agent.slack.delivery.SlackChannelRateGate;
import com.java.system.agent.slack.delivery.SlackDeliveryAdapter;
import com.java.system.agent.slack.source.SlackMentionNormalizer;
import com.java.system.agent.slack.source.SlackSourceIdentityCodec;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.request.chat.ChatPostMessageRequest;
import com.slack.api.methods.response.chat.ChatPostMessageResponse;
import com.slack.api.model.event.AppMentionEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 驗證 Slack mention 經真實 application workflow 形成 participant-aware session 與 Slack delivery 的流程
 */
class SlackAgentFlowTest {

    private static final Instant NOW = Instant.parse("2030-07-28T10:00:00Z");
    private static final AttemptBudget BUDGET = new AttemptBudget(6, 0, 5, 0, 3, 0, 1, 0);

    @Test
    void serializesOverlappingParticipantsAndDeliversEachReceiptBeforeItsAddressedFinal() throws Exception {
        InMemoryDurableFlowStore store = new InMemoryDurableFlowStore();
        SessionInboxPort inboxPort = new InMemorySessionInboxPort(store);
        DeliveryOutboxPort deliveryPort = new InMemoryDeliveryOutboxPort(store);
        SlackMentionNormalizer normalizer = new SlackMentionNormalizer(
                "UBOT", Clock.fixed(NOW, ZoneOffset.UTC), new SlackSourceIdentityCodec());
        SourceAcceptanceApplicationService sourceAcceptance = new SourceAcceptanceApplicationService(store);
        SourceAdmission alice = sourceAcceptance.accept(normalizer.normalize("Ev-A", mention("UA", "1.1", "Alice question"))
                .orElseThrow()).admission().orElseThrow();
        SourceAdmission bob = sourceAcceptance.accept(normalizer.normalize("Ev-B", mention("UB", "1.2", "Bob question"))
                .orElseThrow()).admission().orElseThrow();

        Optional<InboxClaim> bobClaimWhileAliceRuns = Optional.empty();
        AtomicInteger actionCalls = new AtomicInteger();
        Deque<AgentActionProposal> proposals = new ArrayDeque<>(List.of(
                clarification("Please clarify Alice"), clarification("Please clarify Bob")));
        AgentActionPort actions = context -> {
            Optional<InboxClaim> observedClaim = actionCalls.get() == 0 ? store.claimInboxNext(NOW) : Optional.empty();
            store.recordFirstActionClaimObservation(observedClaim);
            return nextClarification(observedClaim, actionCalls, proposals, context);
        };
        FakeSessionAdapter sessions = new FakeSessionAdapter();
        ValidatedAgentLoop loop = loop(actions, sessions);
        AnalysisApplicationService analysis = new AnalysisApplicationService(loop);
        SessionInboxProcessor inboxProcessor = new SessionInboxProcessor(
                inboxPort, analysis, BUDGET, InboxRetryPolicy.defaults());
        InboxWorkApplicationService inboxWork = new InboxWorkApplicationService(
                inboxPort, inboxProcessor, new ClaimAdmissionCoordinator());

        MethodsClient methods = mock(MethodsClient.class);
        AtomicInteger providerMessages = new AtomicInteger();
        when(methods.chatPostMessage(any(ChatPostMessageRequest.class))).thenAnswer(invocation -> successfulResponse(
                "provider-" + providerMessages.incrementAndGet()));
        SlackDeliveryAdapter deliveryAdapter = new SlackDeliveryAdapter(
                methods, "xoxb-test", new SlackSourceIdentityCodec(), new SlackChannelRateGate());
        DeliveryProcessor deliveryProcessor = new DeliveryProcessor(deliveryPort, deliveryAdapter,
                new DeliveryRetryPolicy(Duration.ofSeconds(1), Duration.ofMinutes(1), attempt -> Duration.ZERO));
        DeliveryWorkApplicationService deliveryWork = new DeliveryWorkApplicationService(
                deliveryPort, deliveryProcessor, new ClaimAdmissionCoordinator());

        assertThat(alice.sessionId()).isEqualTo(bob.sessionId());
        assertThat(alice.sessionSequence()).isZero();
        assertThat(bob.sessionSequence()).isOne();
        assertThat(alice.inboxMessageId()).isNotEqualTo(bob.inboxMessageId());
        assertThat(alice.runId()).isNotEqualTo(bob.runId());

        drain(deliveryWork, NOW);
        assertThat(inboxWork.processNext(NOW)).hasValue(InboxProcessingOutcome.COMPLETED);
        bobClaimWhileAliceRuns = store.claimObservedDuringFirstAction();
        assertThat(bobClaimWhileAliceRuns).isEmpty();
        assertThat(actionCalls).hasValue(1);
        drain(deliveryWork, NOW.plusSeconds(2));

        assertThat(inboxWork.processNext(NOW)).hasValue(InboxProcessingOutcome.COMPLETED);
        assertThat(actionCalls).hasValue(2);
        drain(deliveryWork, NOW.plusSeconds(4));

        ArgumentCaptor<ChatPostMessageRequest> requests = ArgumentCaptor.forClass(ChatPostMessageRequest.class);
        verify(methods, times(4)).chatPostMessage(requests.capture());
        assertThat(requests.getAllValues()).extracting(ChatPostMessageRequest::getText).containsExactly(
                "<@UA> 已接收",
                "<@UB> 已接收",
                "<@UA> Please clarify Alice",
                "<@UB> Please clarify Bob");
        assertThat(store.deliveries()).filteredOn(message -> message.kind() == DeliveryKind.FINAL_RESPONSE)
                .allSatisfy(finalMessage -> assertThat(store.deliveries().indexOf(finalMessage)).isGreaterThan(
                        store.deliveryIndex(finalMessage.inboxMessageId(), DeliveryKind.RECEIPT)));
        assertThat(sessions.read(alice.sessionId()).turns()).extracting(ConversationTurn::participant)
                .extracting(ParticipantRef::participantKey).containsExactly("UA", "UB");
    }

    private static AgentActionProposal clarification(String question) {
        return new AgentActionProposal.Proposed(new ClarifyAction(question, List.of(), "Need user detail"));
    }

    private static AgentActionProposal nextClarification(
            Optional<InboxClaim> observedClaim,
            AtomicInteger actionCalls,
            Deque<AgentActionProposal> proposals,
            AgentPromptContext context) {
        Objects.requireNonNull(context, "agent prompt context must not be null");
        int call = actionCalls.incrementAndGet();
        if (call == 1 && observedClaim.isPresent()) {
            throw new IllegalStateException("later same-session inbox must not be claimable during the first action");
        }
        return proposals.removeFirst();
    }

    private static ValidatedAgentLoop loop(AgentActionPort actions, FakeSessionAdapter sessions) {
        FakeAttemptIdGenerator attempts = new FakeAttemptIdGenerator().register(
                new AnalysisAttemptId("attempt-alice"), new AnalysisAttemptId("attempt-bob"));
        AgentTransitionCommitter committer = new AgentTransitionCommitter(
                new AgentStateReducer(), new InMemoryAgentTransitionPort());
        return new ValidatedAgentLoop(
                actions,
                invocation -> {
                    throw new IllegalStateException("clarification flow must not execute a capability");
                },
                (mode, context) -> new AnswerVerificationResult.ContractAccepted(),
                AnswerVerificationMode.CONTRACT_ONLY,
                sessions,
                List::of,
                List::of,
                repositoryId -> {
                    throw new IllegalStateException("clarification flow must not read a repository revision");
                },
                new FakeCancellationAdapter(),
                attempts,
                new AgentActionValidator(),
                new AnswerDocumentValidator(),
                new AnswerVerdictValidator(),
                committer,
                new ContextIssuer());
    }

    private static ChatPostMessageResponse successfulResponse(String timestamp) {
        ChatPostMessageResponse response = new ChatPostMessageResponse();
        response.setOk(true);
        response.setTs(timestamp);
        return response;
    }

    private static void drain(DeliveryWorkApplicationService deliveryWork, Instant start) {
        Instant current = start;
        while (deliveryWork.processNext(current).isPresent()) {
            current = current.plusSeconds(1);
        }
    }

    private static AppMentionEvent mention(String user, String timestamp, String question) {
        AppMentionEvent event = new AppMentionEvent();
        event.setTeam("T1");
        event.setChannel("C1");
        event.setUser(user);
        event.setText("<@UBOT> " + question);
        event.setTs(timestamp);
        event.setThreadTs("1.0");
        return event;
    }

    /**
     * 在測試中同時維護 source、session inbox 與 delivery outbox 原子關係的持久化邊界替身
     */
    private static final class InMemoryDurableFlowStore implements SourceAcceptancePort {

        private final Map<InboxMessageId, InboxMessage> inboxes = new LinkedHashMap<>();
        private final Map<DeliveryId, DeliveryMessage> deliveries = new LinkedHashMap<>();
        private final Map<SourceMessageId, SourceAdmission> admissions = new LinkedHashMap<>();
        private final Map<SessionSourceRef, SessionId> sessions = new LinkedHashMap<>();
        private Optional<InboxClaim> firstActionClaimObservation = Optional.empty();
        private int nextInboxId = 1;
        private int nextRunId = 1;
        private int nextDeliveryId = 1;

        @Override
        public synchronized SourceAcceptance accept(NormalizedSourceEvent event) {
            SourceAdmission duplicate = admissions.get(event.sourceMessageId());
            if (Objects.nonNull(duplicate)) {
                return new SourceAcceptance(SourceAcceptanceStatus.DUPLICATE, Optional.of(duplicate), Optional.empty());
            }
            SessionId sessionId = sessions.computeIfAbsent(event.sessionSource(), source -> new SessionId("session-1"));
            long sequence = inboxes.values().stream().filter(message -> message.sessionId().equals(sessionId)).count();
            InboxMessageId inboxId = new InboxMessageId("inbox-" + nextInboxId++);
            AnalysisRunId runId = new AnalysisRunId("run-" + nextRunId++);
            InboxMessage inbox = new InboxMessage(
                    inboxId, event.sessionSource(), event.sourceMessageId(), sessionId, sequence, runId, event.participant(),
                    event.sourceText(), event.questionText(), InboxMessageStatus.PENDING, 0, event.receivedAt(),
                    Optional.empty(), Optional.empty(), Optional.empty());
            inboxes.put(inboxId, inbox);
            DeliveryMessage receipt = receipt(inbox, event.receivedAt());
            deliveries.put(receipt.deliveryId(), receipt);
            SourceAdmission admission = new SourceAdmission(inboxId, sessionId, sequence, runId);
            admissions.put(event.sourceMessageId(), admission);
            return new SourceAcceptance(SourceAcceptanceStatus.ACCEPTED, Optional.of(admission), Optional.empty());
        }

        private synchronized Optional<InboxClaim> claimInboxNext(Instant now) {
            if (inboxes.values().stream().anyMatch(message -> message.status() == InboxMessageStatus.PROCESSING)) {
                return Optional.empty();
            }
            Optional<InboxMessage> next = inboxes.values().stream()
                    .filter(message -> message.status() == InboxMessageStatus.PENDING)
                    .filter(message -> !message.availableAt().isAfter(now))
                    .filter(this::isSessionHead)
                    .min(Comparator.comparing(InboxMessage::availableAt));
            if (next.isEmpty()) {
                return Optional.empty();
            }
            InboxMessage pending = next.orElseThrow();
            InboxMessage processing = new InboxMessage(
                    pending.inboxMessageId(), pending.source(), pending.sourceMessageId(), pending.sessionId(),
                    pending.sessionSequence(), pending.runId(), pending.participant(), pending.sourceText(), pending.questionText(),
                    InboxMessageStatus.PROCESSING, Math.incrementExact(pending.attemptCount()), pending.availableAt(),
                    Optional.of(now), pending.deferReason(), pending.lastFailure());
            inboxes.put(processing.inboxMessageId(), processing);
            return Optional.of(new InboxClaim(processing));
        }

        private synchronized void completeWithFinal(
                InboxClaim claim,
                AnswerQuestionResult result,
                Instant completedAt) {
            InboxMessage processing = processing(claim);
            InboxMessage completed = new InboxMessage(
                    processing.inboxMessageId(), processing.source(), processing.sourceMessageId(), processing.sessionId(),
                    processing.sessionSequence(), processing.runId(), processing.participant(), processing.sourceText(),
                    processing.questionText(), InboxMessageStatus.COMPLETED, processing.attemptCount(), processing.availableAt(),
                    Optional.empty(), Optional.empty(), Optional.empty());
            inboxes.put(completed.inboxMessageId(), completed);
            DeliveryMessage receipt = deliveryFor(completed.inboxMessageId(), DeliveryKind.RECEIPT);
            DeliveryStatus status = receipt.status() == DeliveryStatus.DELIVERED
                    ? DeliveryStatus.PENDING
                    : DeliveryStatus.WAITING_FOR_RECEIPT;
            DeliveryId deliveryId = new DeliveryId("delivery-" + nextDeliveryId++);
            deliveries.put(deliveryId, new DeliveryMessage(
                    deliveryId, completed.inboxMessageId(), completed.runId(),
                    DeliveryKind.FINAL_RESPONSE, Optional.of(result.responseKind()), Optional.of(result.outcome()),
                    completed.source(), completed.participant(), result.responseText(), status, 0, completedAt,
                    Optional.empty(), Optional.empty(), completedAt, completedAt));
        }

        private synchronized void retry(InboxClaim claim, InboxFailure failure, Instant availableAt) {
            throw new UnsupportedOperationException("clarification flow does not retry inbox work");
        }

        private synchronized void failWithFinal(InboxClaim claim, InboxFailure failure, String safeResponseText, Instant failedAt) {
            throw new UnsupportedOperationException("clarification flow does not fail inbox work");
        }

        private synchronized void deferForCapacity(InboxClaim claim, Instant retryAt) {
            throw new UnsupportedOperationException("clarification flow does not defer inbox work");
        }

        private synchronized int recoverInterruptedInbox(Instant recoveredAt) {
            return 0;
        }

        private synchronized Optional<DeliveryClaim> claimDeliveryNext(Instant now) {
            Optional<DeliveryMessage> next = deliveries.values().stream()
                    .filter(message -> message.status() == DeliveryStatus.PENDING)
                    .filter(message -> !message.nextAttemptAt().isAfter(now))
                    .min(Comparator.comparing(DeliveryMessage::nextAttemptAt));
            if (next.isEmpty()) {
                return Optional.empty();
            }
            DeliveryMessage pending = next.orElseThrow();
            DeliveryMessage processing = copyDelivery(
                    pending, DeliveryStatus.PROCESSING, Math.incrementExact(pending.attemptCount()), Optional.empty(), Optional.empty(), now);
            deliveries.put(processing.deliveryId(), processing);
            return Optional.of(new DeliveryClaim(processing));
        }

        private synchronized void recordDelivered(DeliveryClaim claim, String providerMessageId, Instant deliveredAt) {
            DeliveryMessage processing = claimedDelivery(claim);
            deliveries.put(processing.deliveryId(), copyDelivery(
                    processing, DeliveryStatus.DELIVERED, processing.attemptCount(), Optional.empty(), Optional.of(providerMessageId), deliveredAt));
            if (processing.kind() == DeliveryKind.RECEIPT) {
                deliveries.values().stream()
                        .filter(message -> message.inboxMessageId().equals(processing.inboxMessageId()))
                        .filter(message -> message.kind() == DeliveryKind.FINAL_RESPONSE)
                        .filter(message -> message.status() == DeliveryStatus.WAITING_FOR_RECEIPT)
                        .forEach(message -> deliveries.put(message.deliveryId(), copyDelivery(
                                message, DeliveryStatus.PENDING, message.attemptCount(), Optional.empty(), Optional.empty(), deliveredAt)));
            }
        }

        private synchronized void recordRetry(DeliveryClaim claim, DeliveryFailure failure, Instant retryAt, Instant updatedAt) {
            throw new UnsupportedOperationException("Slack success flow does not retry delivery");
        }

        private synchronized void recordBlocked(DeliveryClaim claim, DeliveryFailure failure, Instant blockedAt) {
            throw new UnsupportedOperationException("Slack success flow does not block delivery");
        }

        private synchronized int recoverInterruptedDelivery(Instant recoveredAt) {
            return 0;
        }

        private void recordFirstActionClaimObservation(Optional<InboxClaim> observation) {
            firstActionClaimObservation = observation;
        }

        private Optional<InboxClaim> claimObservedDuringFirstAction() {
            return firstActionClaimObservation;
        }

        private List<DeliveryMessage> deliveries() {
            return List.copyOf(deliveries.values());
        }

        private int deliveryIndex(InboxMessageId inboxMessageId, DeliveryKind kind) {
            return IntStream.range(0, deliveries().size())
                    .filter(index -> deliveries().get(index).inboxMessageId().equals(inboxMessageId))
                    .filter(index -> deliveries().get(index).kind() == kind)
                    .findFirst()
                    .orElseThrow();
        }

        private boolean isSessionHead(InboxMessage candidate) {
            return inboxes.values().stream().noneMatch(earlier -> earlier.sessionId().equals(candidate.sessionId())
                    && earlier.sessionSequence() < candidate.sessionSequence()
                    && (earlier.status() == InboxMessageStatus.PENDING || earlier.status() == InboxMessageStatus.PROCESSING));
        }

        private InboxMessage processing(InboxClaim claim) {
            InboxMessage message = Objects.requireNonNull(inboxes.get(claim.message().inboxMessageId()), "claimed inbox must exist");
            if (!message.equals(claim.message()) || message.status() != InboxMessageStatus.PROCESSING) {
                throw new IllegalStateException("inbox completion requires the current processing claim");
            }
            return message;
        }

        private DeliveryMessage claimedDelivery(DeliveryClaim claim) {
            DeliveryMessage message = Objects.requireNonNull(deliveries.get(claim.message().deliveryId()), "claimed delivery must exist");
            if (!message.equals(claim.message()) || message.status() != DeliveryStatus.PROCESSING) {
                throw new IllegalStateException("delivery result requires the current processing claim");
            }
            return message;
        }

        private DeliveryMessage deliveryFor(InboxMessageId inboxMessageId, DeliveryKind kind) {
            return deliveries.values().stream()
                    .filter(message -> message.inboxMessageId().equals(inboxMessageId))
                    .filter(message -> message.kind() == kind)
                    .findFirst()
                    .orElseThrow();
        }

        private DeliveryMessage receipt(InboxMessage inbox, Instant createdAt) {
            DeliveryId deliveryId = new DeliveryId("delivery-" + nextDeliveryId++);
            return new DeliveryMessage(
                    deliveryId, inbox.inboxMessageId(), inbox.runId(), DeliveryKind.RECEIPT, Optional.empty(), Optional.empty(),
                    inbox.source(), inbox.participant(), "已接收", DeliveryStatus.PENDING, 0, createdAt,
                    Optional.empty(), Optional.empty(), createdAt, createdAt);
        }

        private static DeliveryMessage copyDelivery(
                DeliveryMessage message,
                DeliveryStatus status,
                int attemptCount,
                Optional<DeliveryFailure> failure,
                Optional<String> providerMessageId,
                Instant updatedAt) {
            return new DeliveryMessage(
                    message.deliveryId(), message.inboxMessageId(), message.runId(), message.kind(), message.responseKind(),
                    message.outcome(), message.sessionSource(), message.participant(), message.responseText(), status,
                    attemptCount, updatedAt, failure, providerMessageId, message.createdAt(), updatedAt);
        }
    }

    /**
     * 將測試 durable state 的 inbox 操作暴露為正式 session inbox port
     */
    private record InMemorySessionInboxPort(InMemoryDurableFlowStore store) implements SessionInboxPort {

        @Override
        public Optional<InboxClaim> claimNext(Instant now) {
            return store.claimInboxNext(now);
        }

        @Override
        public void completeWithFinal(
                InboxClaim claim,
                AnswerQuestionResult result,
                Instant completedAt) {
            store.completeWithFinal(claim, result, completedAt);
        }

        @Override
        public void retry(InboxClaim claim, InboxFailure failure, Instant availableAt) {
            store.retry(claim, failure, availableAt);
        }

        @Override
        public void failWithFinal(InboxClaim claim, InboxFailure failure, String safeResponseText, Instant failedAt) {
            store.failWithFinal(claim, failure, safeResponseText, failedAt);
        }

        @Override
        public void deferForCapacity(InboxClaim claim, Instant retryAt) {
            store.deferForCapacity(claim, retryAt);
        }

        @Override
        public int recoverInterrupted(Instant recoveredAt) {
            return store.recoverInterruptedInbox(recoveredAt);
        }
    }

    /**
     * 將測試 durable state 的 delivery 操作暴露為正式 delivery outbox port
     */
    private record InMemoryDeliveryOutboxPort(InMemoryDurableFlowStore store) implements DeliveryOutboxPort {

        @Override
        public Optional<DeliveryClaim> claimNext(Instant now) {
            return store.claimDeliveryNext(now);
        }

        @Override
        public void recordDelivered(DeliveryClaim claim, String providerMessageId, Instant deliveredAt) {
            store.recordDelivered(claim, providerMessageId, deliveredAt);
        }

        @Override
        public void recordRetry(DeliveryClaim claim, DeliveryFailure failure, Instant retryAt, Instant updatedAt) {
            store.recordRetry(claim, failure, retryAt, updatedAt);
        }

        @Override
        public void recordBlocked(DeliveryClaim claim, DeliveryFailure failure, Instant blockedAt) {
            store.recordBlocked(claim, failure, blockedAt);
        }

        @Override
        public int recoverInterrupted(Instant recoveredAt) {
            return store.recoverInterruptedDelivery(recoveredAt);
        }
    }

    /**
     * 以 revision compare-and-set 模擬 loop 所需的 agent transition persistence 邊界
     */
    private static final class InMemoryAgentTransitionPort implements AgentTransitionPort {

        private final Map<AnalysisRunId, AgentRunState> states = new LinkedHashMap<>();

        @Override
        public synchronized AgentRunState bootstrap(AgentBootstrap bootstrap) {
            AnalysisRunId runId = bootstrap.finalTransition().candidateState().runId();
            if (Objects.nonNull(states.get(runId))) {
                throw new AgentTransitionConflictException("run already exists");
            }
            AgentRunState state = bootstrap.finalTransition().candidateState();
            states.put(runId, state);
            return state;
        }

        @Override
        public synchronized AgentRunState commit(AgentTransition transition) {
            return commitCurrent(transition);
        }

        @Override
        public synchronized AgentRunState commitTerminalAcceptance(AgentTransition transition) {
            return commitCurrent(transition);
        }

        @Override
        public synchronized Optional<AgentRunState> findByRunId(AnalysisRunId runId) {
            return Optional.ofNullable(states.get(runId));
        }

        private AgentRunState commitCurrent(AgentTransition transition) {
            AgentRunState existing = states.get(transition.candidateState().runId());
            if (Objects.isNull(existing) || existing.stateRevision() != transition.event().expectedStateRevision()) {
                throw new AgentTransitionConflictException("stale transition revision");
            }
            AgentRunState state = transition.candidateState();
            states.put(state.runId(), state);
            return state;
        }
    }
}

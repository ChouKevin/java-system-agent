package com.java.system.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.agent.interaction.application.InboxRetryPolicy;
import com.java.system.agent.interaction.application.InboxWorkApplicationService;
import com.java.system.agent.interaction.application.ClaimAdmissionCoordinator;
import com.java.system.agent.interaction.application.SessionInboxProcessor;
import com.java.system.agent.interaction.application.SourceAcceptanceApplicationService;
import com.java.system.agent.persistence.document.AgentEventDocumentCodec;
import com.java.system.agent.persistence.document.AgentStateDocumentCodec;
import com.java.system.agent.persistence.jdbc.PostgresAgentTransitionAdapter;
import com.java.system.agent.persistence.jdbc.PostgresAnalysisCancellationAdapter;
import com.java.system.agent.persistence.jdbc.PostgresSessionAdapter;
import com.java.system.agent.persistence.jdbc.PostgresSessionInboxAdapter;
import com.java.system.agent.persistence.jdbc.PostgresDeliveryOutboxAdapter;
import com.java.system.agent.persistence.jdbc.PostgresSourceAcceptanceAdapter;
import com.java.system.agent.persistence.jdbc.UuidInboxIdentityGenerator;
import com.java.system.agent.answering.application.AnalysisApplicationService;
import com.java.system.agent.answering.application.ContextIssuer;
import com.java.system.agent.answering.application.ValidatedAgentLoop;
import com.java.system.agent.answering.application.state.AgentStateReducer;
import com.java.system.agent.answering.application.state.AgentTransitionCommitter;
import com.java.system.agent.answering.application.validation.AgentActionValidator;
import com.java.system.agent.answering.application.validation.AnswerDocumentValidator;
import com.java.system.agent.answering.application.validation.AnswerVerdictValidator;
import com.java.system.agent.answering.domain.run.AnalysisAttemptId;
import com.java.system.agent.interaction.application.InboxLifecycleMetrics;
import com.java.system.agent.answering.domain.run.AttemptBudget;
import com.java.system.agent.answering.port.out.AnalysisAttemptIdGenerator;
import com.java.system.agent.answering.port.out.AgentActionPort;
import com.java.system.agent.answering.port.out.AgentTransitionPort;
import com.java.system.agent.answering.port.out.AnswerVerificationPort;
import com.java.system.agent.answering.port.out.CapabilityCatalogPort;
import com.java.system.agent.answering.port.out.CapabilityExecutionPort;
import com.java.system.agent.answering.port.out.RepositoryCatalogPort;
import com.java.system.agent.answering.port.out.RepositoryRevisionPort;
import com.java.system.agent.answering.port.out.SessionPort;
import com.java.system.agent.answering.port.out.AnalysisCancellationPort;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 在 agent-runtime profile 中組裝唯一 production Agent graph 的 privileged bootstrap
 */
@Configuration(proxyBeanMethods = false)
@Profile("agent-runtime")
@EnableConfigurationProperties({AgentRuntimeProperties.class, AgentCodebaseProperties.class})
public final class AgentRuntimeConfiguration {

    @Bean
    AgentStateDocumentCodec agentStateDocumentCodec(ObjectMapper objectMapper) {
        return new AgentStateDocumentCodec(objectMapper);
    }

    @Bean
    AgentEventDocumentCodec agentEventDocumentCodec(ObjectMapper objectMapper) {
        return new AgentEventDocumentCodec(objectMapper);
    }

    @Bean
    PostgresAgentTransitionAdapter postgresAgentTransitionAdapter(
            JdbcClient jdbcClient,
            TransactionTemplate transactionTemplate,
            AgentStateDocumentCodec stateCodec,
            AgentEventDocumentCodec eventCodec) {
        return new PostgresAgentTransitionAdapter(jdbcClient, transactionTemplate, stateCodec, eventCodec);
    }

    @Bean
    PostgresSessionAdapter postgresSessionAdapter(JdbcClient jdbcClient, TransactionTemplate transactionTemplate) {
        return new PostgresSessionAdapter(jdbcClient, transactionTemplate);
    }

    @Bean
    UuidInboxIdentityGenerator uuidInboxIdentityGenerator() {
        return new UuidInboxIdentityGenerator();
    }

    @Bean
    PostgresSessionInboxAdapter postgresSessionInboxAdapter(
            JdbcClient jdbcClient,
            TransactionTemplate transactionTemplate,
            UuidInboxIdentityGenerator identityGenerator) {
        return new PostgresSessionInboxAdapter(jdbcClient, transactionTemplate, identityGenerator);
    }

    @Bean
    PostgresSourceAcceptanceAdapter postgresSourceAcceptanceAdapter(
            JdbcClient jdbcClient,
            TransactionTemplate transactionTemplate,
            UuidInboxIdentityGenerator identityGenerator) {
        return new PostgresSourceAcceptanceAdapter(jdbcClient, transactionTemplate, identityGenerator);
    }

    @Bean
    PostgresDeliveryOutboxAdapter postgresDeliveryOutboxAdapter(
            JdbcClient jdbcClient,
            TransactionTemplate transactionTemplate) {
        return new PostgresDeliveryOutboxAdapter(jdbcClient, transactionTemplate);
    }

    @Bean
    PostgresAnalysisCancellationAdapter postgresAnalysisCancellationAdapter(JdbcClient jdbcClient) {
        return new PostgresAnalysisCancellationAdapter(jdbcClient);
    }

    @Bean
    AnalysisAttemptIdGenerator analysisAttemptIdGenerator() {
        return (runId, attemptSequence) -> new AnalysisAttemptId(runId.value() + ":A" + attemptSequence);
    }

    @Bean
    AgentActionValidator agentActionValidator() {
        return new AgentActionValidator();
    }

    @Bean
    AnswerDocumentValidator answerDocumentValidator() {
        return new AnswerDocumentValidator();
    }

    @Bean
    AnswerVerdictValidator answerVerdictValidator() {
        return new AnswerVerdictValidator();
    }

    @Bean
    AgentStateReducer agentStateReducer() {
        return new AgentStateReducer();
    }

    @Bean
    AgentTransitionCommitter agentTransitionCommitter(
            AgentStateReducer reducer,
            AgentTransitionPort transitionPort) {
        return new AgentTransitionCommitter(reducer, transitionPort);
    }

    @Bean
    ContextIssuer contextIssuer() {
        return new ContextIssuer();
    }

    @Bean
    ValidatedAgentLoop validatedAgentLoop(
            AgentActionPort actionPort,
            CapabilityExecutionPort capabilityExecutionPort,
            AnswerVerificationPort verificationPort,
            AgentRuntimeProperties runtimeProperties,
            SessionPort sessionPort,
            RepositoryCatalogPort repositoryCatalogPort,
            CapabilityCatalogPort capabilityCatalogPort,
            RepositoryRevisionPort repositoryRevisionPort,
            AnalysisCancellationPort cancellationPort,
            AnalysisAttemptIdGenerator attemptIdGenerator,
            AgentActionValidator actionValidator,
            AnswerDocumentValidator documentValidator,
            AnswerVerdictValidator verdictValidator,
            AgentTransitionCommitter transitionCommitter,
            ContextIssuer contextIssuer) {
        return new ValidatedAgentLoop(
                actionPort,
                capabilityExecutionPort,
                verificationPort,
                runtimeProperties.mode(),
                sessionPort,
                repositoryCatalogPort,
                capabilityCatalogPort,
                repositoryRevisionPort,
                cancellationPort,
                attemptIdGenerator,
                actionValidator,
                documentValidator,
                verdictValidator,
                transitionCommitter,
                contextIssuer);
    }

    @Bean
    AnalysisApplicationService analysisApplicationService(ValidatedAgentLoop loop) {
        return new AnalysisApplicationService(loop);
    }

    @Bean
    AttemptBudget initialAttemptBudget() {
        return new AttemptBudget(6, 0, 5, 0, 3, 0, 1, 0);
    }

    @Bean
    InboxRetryPolicy inboxRetryPolicy() {
        return InboxRetryPolicy.defaults();
    }

    @Bean
    SessionInboxProcessor sessionInboxProcessor(
            PostgresSessionInboxAdapter inboxPort,
            AnalysisApplicationService useCase,
            AttemptBudget attemptBudget,
            InboxRetryPolicy retryPolicy,
            InboxLifecycleMetrics metrics) {
        return new SessionInboxProcessor(inboxPort, useCase, attemptBudget, retryPolicy, metrics);
    }

    @Bean
    InboxWorkApplicationService inboxWorkApplicationService(
            PostgresSessionInboxAdapter inboxPort,
            SessionInboxProcessor processor,
            ClaimAdmissionCoordinator claimAdmission) {
        return new InboxWorkApplicationService(inboxPort, processor, claimAdmission);
    }

    @Bean
    ClaimAdmissionCoordinator claimAdmissionCoordinator() {
        return new ClaimAdmissionCoordinator();
    }

    @Bean
    SourceAcceptanceApplicationService sourceAcceptanceApplicationService(
            PostgresSourceAcceptanceAdapter sourceAcceptancePort,
            InboxLifecycleMetrics metrics) {
        return new SourceAcceptanceApplicationService(sourceAcceptancePort, metrics);
    }
}

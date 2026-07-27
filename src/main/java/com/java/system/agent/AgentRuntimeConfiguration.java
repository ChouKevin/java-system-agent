package com.java.system.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.agent.inbox.application.InboxRetryPolicy;
import com.java.system.agent.inbox.application.SessionInboxApplicationService;
import com.java.system.agent.inbox.application.SessionInboxProcessor;
import com.java.system.agent.persistence.document.AgentEventDocumentCodec;
import com.java.system.agent.persistence.document.AgentStateDocumentCodec;
import com.java.system.agent.persistence.jdbc.PostgresAgentTransitionAdapter;
import com.java.system.agent.persistence.jdbc.PostgresAnalysisCancellationAdapter;
import com.java.system.agent.persistence.jdbc.PostgresSessionAdapter;
import com.java.system.agent.persistence.jdbc.PostgresSessionInboxAdapter;
import com.java.system.agent.persistence.jdbc.UuidInboxIdentityGenerator;
import com.java.system.agent.runtime.application.AnalysisApplicationService;
import com.java.system.agent.runtime.application.ContextIssuer;
import com.java.system.agent.runtime.application.ValidatedAgentLoop;
import com.java.system.agent.runtime.application.state.AgentStateReducer;
import com.java.system.agent.runtime.application.state.AgentTransitionCommitter;
import com.java.system.agent.runtime.application.validation.AgentActionValidator;
import com.java.system.agent.runtime.application.validation.AnswerDocumentValidator;
import com.java.system.agent.runtime.application.validation.AnswerVerdictValidator;
import com.java.system.agent.runtime.domain.run.AnalysisAttemptId;
import com.java.system.agent.runtime.domain.run.AttemptBudget;
import com.java.system.agent.runtime.port.out.AnalysisAttemptIdGenerator;
import com.java.system.agent.runtime.port.out.AgentActionPort;
import com.java.system.agent.runtime.port.out.AgentTransitionPort;
import com.java.system.agent.runtime.port.out.AnswerVerificationPort;
import com.java.system.agent.runtime.port.out.CapabilityCatalogPort;
import com.java.system.agent.runtime.port.out.CapabilityExecutionPort;
import com.java.system.agent.runtime.port.out.RepositoryCatalogPort;
import com.java.system.agent.runtime.port.out.RepositoryRevisionPort;
import com.java.system.agent.runtime.port.out.SessionPort;
import com.java.system.agent.runtime.port.out.AnalysisCancellationPort;
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
        return new AttemptBudget(6, 0, 5, 0, 3, 0, 1, 0, 1, 0);
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
            InboxRetryPolicy retryPolicy) {
        return new SessionInboxProcessor(inboxPort, useCase, attemptBudget, retryPolicy);
    }

    @Bean
    SessionInboxApplicationService sessionInboxApplicationService(PostgresSessionInboxAdapter inboxPort) {
        return new SessionInboxApplicationService(inboxPort);
    }
}

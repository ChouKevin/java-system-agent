package com.java.system.agent.codeintelligence.semantic;

import com.java.system.agent.answering.domain.candidate.CandidateKind;
import com.java.system.agent.answering.domain.candidate.IssuedCandidate;
import com.java.system.agent.answering.domain.candidate.SemanticTargetCandidate;
import com.java.system.agent.answering.domain.capability.CapabilityInputPayload;
import com.java.system.agent.answering.domain.capability.CapabilityPolicy;
import com.java.system.agent.answering.domain.evidence.SemanticTarget;
import com.java.system.agent.answering.domain.handle.CandidateHandle;
import com.java.system.agent.answering.domain.handle.HandleBinding;
import com.java.system.agent.answering.domain.run.AnalysisAttemptId;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import com.java.system.agent.answering.domain.scope.RepositoryId;
import com.java.system.agent.answering.domain.scope.RepositoryRevision;
import com.java.system.agent.answering.domain.scope.RevisionVector;
import com.java.system.agent.answering.port.out.CapabilityInvocation;
import com.java.system.agent.codeintelligence.semantic.dto.SemanticDtos;

import java.util.List;
import java.util.Set;

/**
 * Code intelligence mapper 測試共用的 answering-issued target invocation 建構器
 */
final class JavaSemanticServiceHttpAdapterTestHelper {

    private JavaSemanticServiceHttpAdapterTestHelper() {
    }

    static CapabilityInvocation targetInvocation() {
        SemanticTarget target = new JavaSemanticResultMapper().semanticTarget(new SemanticDtos.MethodTarget(
                "src/OrderService.java", "com.example", "OrderService", "find", List.of("java.lang.String")));
        return targetInvocation(target);
    }

    static CapabilityInvocation targetInvocation(SemanticDtos.MethodTarget target) {
        return targetInvocation(new JavaSemanticResultMapper().semanticTarget(target));
    }

    private static CapabilityInvocation targetInvocation(SemanticTarget target) {
        RepositoryId repositoryId = new RepositoryId("orders");
        RepositoryRevision revision = new RepositoryRevision("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        RevisionVector revisions = RevisionVector.empty().pin(repositoryId, revision);
        IssuedCandidate candidate = new IssuedCandidate(
                new CandidateHandle("candidate-2", new HandleBinding(new AnalysisRunId("run-1"),
                        new AnalysisAttemptId("attempt-1"), revisions), CandidateKind.SEMANTIC_TARGET),
                new SemanticTargetCandidate(repositoryId, revision, target, "Order lookup"));
        CapabilityPolicy capability = new CapabilityPolicy("codebase_outgoing_call_graph", "v1",
                Set.of(CandidateKind.SEMANTIC_TARGET), 1, 1);
        return new CapabilityInvocation(capability, "Trace orders",
                new CapabilityInputPayload("{\"depth\":1}"), revisions);
    }
}

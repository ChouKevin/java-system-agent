package com.java.system.agent.codebase.semantic;

import com.java.system.agent.runtime.domain.candidate.CandidateKind;
import com.java.system.agent.runtime.domain.candidate.IssuedCandidate;
import com.java.system.agent.runtime.domain.candidate.SemanticTargetCandidate;
import com.java.system.agent.runtime.domain.capability.CapabilityPolicy;
import com.java.system.agent.runtime.domain.evidence.SemanticTarget;
import com.java.system.agent.runtime.domain.handle.CandidateHandle;
import com.java.system.agent.runtime.domain.handle.HandleBinding;
import com.java.system.agent.runtime.domain.run.AnalysisAttemptId;
import com.java.system.agent.runtime.domain.run.AnalysisRunId;
import com.java.system.agent.runtime.domain.scope.RepositoryId;
import com.java.system.agent.runtime.domain.scope.RepositoryRevision;
import com.java.system.agent.runtime.domain.scope.RevisionVector;
import com.java.system.agent.runtime.port.out.CapabilityInvocation;
import com.java.system.agent.codebase.semantic.dto.SemanticDtos;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Codebase mapper 測試共用的 runtime-issued target invocation 建構器
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
        CapabilityPolicy capability = new CapabilityPolicy("codebase.outgoing-call-graph", "v1",
                Set.of(CandidateKind.SEMANTIC_TARGET), 1, 1);
        return new CapabilityInvocation(capability, List.of(candidate), "Trace orders", Map.of("depth", "1"), revisions);
    }
}

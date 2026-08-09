package com.java.system.agent.codeintelligence.semantic;

import com.java.system.agent.answering.domain.evidence.EvidenceRef;
import com.java.system.agent.answering.domain.evidence.SemanticTarget;
import com.java.system.agent.answering.domain.scope.RepositoryId;
import com.java.system.agent.answering.domain.scope.RepositoryRevision;
import com.java.system.agent.codeintelligence.semantic.dto.SemanticDtos;

import java.util.List;

/**
 * 將已驗證的 Semantic provider metadata 投影為可引用證據，位於結果轉譯流程
 */
final class JavaSemanticMetadataEvidenceMapper {

    private static final String SOURCE_SERVICE = "java-semantic-service";

    EvidenceRef entryPoint(RepositoryId repositoryId, RepositoryRevision revision,
                           SemanticDtos.EntryPointClassResponse entryPoint,
                           SemanticDtos.EntryPointMethodResponse method, SemanticTarget target) {
        String content = "entryPoint; kind=" + method.type() + "; class=" + entryPoint.packageName() + "."
                + entryPoint.className() + "; method=" + method.name() + "; description=" + method.description()
                + entryPointMetadata(method);
        return evidence(repositoryId, revision, target, content);
    }

    EvidenceRef implementation(RepositoryId repositoryId, RepositoryRevision revision,
                               SemanticDtos.MethodTargetPayload requestedTarget,
                               SemanticDtos.MethodImplementationCandidateResponse candidate,
                               SemanticTarget target) {
        String content = "methodImplementation; requested=" + methodTarget(requestedTarget) + "; implementation="
                + methodTarget(candidate.target()) + "; primary=" + candidate.primary() + "; qualifiers="
                + values(candidate.qualifiers()) + "; profiles=" + values(candidate.profiles());
        return evidence(repositoryId, revision, target, content);
    }

    EvidenceRef declaration(RepositoryId repositoryId, RepositoryRevision revision,
                            SemanticDtos.InternalReferenceTargetDeclarationResponse declaration, int totalReferenceCount,
                            SemanticTarget target) {
        String content = "internalReference; reference=declaration; target=" + declaration.target().kind() + "; range="
                + range(declaration.declarationRange()) + "; total=" + totalReferenceCount;
        return evidence(repositoryId, revision, target, content);
    }

    EvidenceRef occurrence(RepositoryId repositoryId, RepositoryRevision revision,
                           SemanticDtos.ReferenceGroupResponse group, int totalReferenceCount,
                           SemanticDtos.ReferenceOccurrenceResponse occurrence, SemanticTarget target) {
        String content = "internalReference; reference=occurrence; context=" + context(group.context()) + "; range="
                + range(occurrence.range()) + "; representativeCount=" + group.representativeReferences().size()
                + "; groupTotal=" + group.limits().totalCount() + "; total=" + totalReferenceCount;
        return evidence(repositoryId, revision, target, content);
    }

    private EvidenceRef evidence(RepositoryId repositoryId, RepositoryRevision revision, SemanticTarget target,
                                 String content) {
        String singleLineContent = JavaSemanticResultMapper.singleLine(content);
        return new EvidenceRef(SOURCE_SERVICE, repositoryId, revision, target, singleLineContent, List.of(),
                JavaSemanticArtifactDigest.fromContent(singleLineContent));
    }

    private String entryPointMetadata(SemanticDtos.EntryPointMethodResponse method) {
        if (method instanceof SemanticDtos.ApiEntryPointMethodResponse api) {
            return "; url=" + api.apiUrl() + "; httpMethods=" + values(api.httpMethods()) + "; swagger="
                    + values(api.swaggerDescriptions());
        }
        if (method instanceof SemanticDtos.MqEntryPointMethodResponse mq) {
            return "; broker=" + mq.broker() + "; destinations=" + values(mq.destinations());
        }
        if (method instanceof SemanticDtos.ScheduleEntryPointMethodResponse schedule) {
            return "; trigger=" + schedule.triggerKind() + ":" + schedule.triggerValue();
        }
        throw new IllegalArgumentException("unsupported entry point method");
    }

    private String context(SemanticDtos.InternalReferenceContextResponse context) {
        if (context instanceof SemanticDtos.InternalReferenceTypeContextResponse type) {
            return type.kind() + ":" + sourceType(type.sourceType());
        }
        if (context instanceof SemanticDtos.InternalReferenceMethodContextResponse method) {
            return method.kind() + ":" + methodTarget(method.method());
        }
        throw new IllegalArgumentException("unsupported internal reference context");
    }

    private String methodTarget(SemanticDtos.MethodTargetPayload target) {
        return sourceType(target.sourceType()) + "." + target.methodName() + "(" + values(target.parameterTypes()) + ")";
    }

    private String sourceType(SemanticDtos.SourceTypeIdentityPayload sourceType) {
        return sourceType.sourceFile() + "#" + sourceType.javaType().className();
    }

    private String range(SemanticDtos.TextRangePayload range) {
        return range.start().line() + ":" + range.start().character() + "-" + range.end().line() + ":"
                + range.end().character();
    }

    private String values(List<String> values) {
        return values.stream().map(JavaSemanticResultMapper::singleLine).reduce((first, second) -> first + "," + second)
                .orElse("");
    }
}

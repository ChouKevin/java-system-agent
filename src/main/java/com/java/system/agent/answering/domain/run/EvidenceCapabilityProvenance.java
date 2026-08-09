package com.java.system.agent.answering.domain.run;

import com.java.system.agent.answering.domain.action.QueryAction;
import com.java.system.agent.answering.domain.capability.CapabilityPolicy;
import com.java.system.agent.answering.domain.evidence.IssuedEvidence;
import com.java.system.agent.answering.domain.handle.CapabilityHandle;
import com.java.system.agent.answering.domain.handle.EvidenceHandle;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 已發行證據與產生該證據之查詢能力的可驗證 run 歷史關聯
 */
public record EvidenceCapabilityProvenance(EvidenceHandle evidenceHandle, CapabilityPolicy capability) {

    public EvidenceCapabilityProvenance {
        Objects.requireNonNull(evidenceHandle, "evidence provenance handle must not be null");
        Objects.requireNonNull(capability, "evidence provenance capability must not be null");
    }

    /**
     * 從已配發 context 與依序保存的模型動作結果建立證據來源，不解析或猜測證據內容。
     */
    public static List<EvidenceCapabilityProvenance> resolve(
            Map<CapabilityHandle, CapabilityPolicy> issuedCapabilities,
            Map<EvidenceHandle, IssuedEvidence> issuedEvidence,
            List<ModelInteraction> modelInteractions) {
        Objects.requireNonNull(issuedCapabilities, "issued capabilities must not be null");
        Objects.requireNonNull(issuedEvidence, "issued evidence must not be null");
        Objects.requireNonNull(modelInteractions, "model interactions must not be null");
        Map<String, CapabilityPolicy> capabilitiesByHandle = capabilitiesByHandle(issuedCapabilities);
        Map<String, EvidenceHandle> evidenceByHandle = evidenceByHandle(issuedEvidence);
        Map<AnalysisAttemptId, QueryAction> pendingQueries = new LinkedHashMap<>();
        Set<EvidenceCapabilityProvenance> resolved = new LinkedHashSet<>();
        for (ModelInteraction interaction : modelInteractions) {
            if (interaction instanceof ModelInteraction.ActionSelected selected) {
                if (selected.action() instanceof QueryAction query) {
                    pendingQueries.put(selected.attemptId(), query);
                } else {
                    pendingQueries.remove(selected.attemptId());
                }
            } else if (interaction instanceof ModelInteraction.ActionResultRecorded recorded) {
                Optional<QueryAction> selectedQuery = Optional.ofNullable(pendingQueries.remove(recorded.attemptId()));
                if (selectedQuery.isPresent() && recorded.result() instanceof ActionResult.QuerySucceeded succeeded) {
                    addResolvedEvidence(
                            resolved,
                            selectedQuery.orElseThrow(),
                            succeeded,
                            capabilitiesByHandle,
                            evidenceByHandle);
                }
            }
        }
        return List.copyOf(resolved);
    }

    private static Map<String, CapabilityPolicy> capabilitiesByHandle(
            Map<CapabilityHandle, CapabilityPolicy> issuedCapabilities) {
        Map<String, CapabilityPolicy> capabilitiesByHandle = new LinkedHashMap<>();
        for (Map.Entry<CapabilityHandle, CapabilityPolicy> entry : issuedCapabilities.entrySet()) {
            capabilitiesByHandle.put(entry.getKey().value(), entry.getValue());
        }
        return capabilitiesByHandle;
    }

    private static Map<String, EvidenceHandle> evidenceByHandle(
            Map<EvidenceHandle, IssuedEvidence> issuedEvidence) {
        Map<String, EvidenceHandle> evidenceByHandle = new LinkedHashMap<>();
        for (EvidenceHandle handle : issuedEvidence.keySet()) {
            evidenceByHandle.put(handle.value(), handle);
        }
        return evidenceByHandle;
    }

    private static void addResolvedEvidence(
            Set<EvidenceCapabilityProvenance> resolved,
            QueryAction query,
            ActionResult.QuerySucceeded succeeded,
            Map<String, CapabilityPolicy> capabilitiesByHandle,
            Map<String, EvidenceHandle> evidenceByHandle) {
        Optional<CapabilityPolicy> capability = Optional.ofNullable(
                capabilitiesByHandle.get(query.capability().value()));
        if (capability.isEmpty()) {
            return;
        }
        for (String handleValue : succeeded.evidenceHandleValues()) {
            Optional<EvidenceHandle> evidenceHandle = Optional.ofNullable(evidenceByHandle.get(handleValue));
            evidenceHandle.ifPresent(handle -> resolved.add(
                    new EvidenceCapabilityProvenance(handle, capability.orElseThrow())));
        }
    }
}

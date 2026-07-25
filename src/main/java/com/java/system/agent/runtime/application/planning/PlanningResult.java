package com.java.system.agent.runtime.application.planning;

import java.util.Objects;
import java.util.Optional;

/**
 * {@link InformationNeedPlanner#plan} 的回傳值：規劃狀態、對應的規劃結果與人可讀的診斷訊息
 *
 * <p>狀態為 {@code PLANNED} 時必須攜帶 {@link PlannedCapability}，其餘狀態一律不能
 * 攜帶，兩者互斥由建構檢查保證</p>
 */
public record PlanningResult(
        PlanningStatus status,
        Optional<PlannedCapability> plannedCapability,
        String diagnosis) {

    public PlanningResult {
        Objects.requireNonNull(status, "planning status must not be null");
        Objects.requireNonNull(plannedCapability, "planned capability must not be null");
        Objects.requireNonNull(diagnosis, "planning diagnosis must not be null");
        diagnosis = diagnosis.trim();
        if (diagnosis.isBlank()) {
            throw new IllegalArgumentException("planning diagnosis must not be blank");
        }
        if (status == PlanningStatus.PLANNED && !plannedCapability.isPresent()) {
            throw new IllegalArgumentException("planned result requires a capability");
        }
        if (status != PlanningStatus.PLANNED && plannedCapability.isPresent()) {
            throw new IllegalArgumentException("non-planned result cannot contain a capability");
        }
    }
}

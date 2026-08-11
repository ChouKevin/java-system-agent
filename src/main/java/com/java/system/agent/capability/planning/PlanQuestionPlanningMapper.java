package com.java.system.agent.capability.planning;

import com.java.system.agent.answering.domain.action.PlanAction;
import com.java.system.agent.answering.domain.plan.InformationNeed;
import com.java.system.agent.answering.domain.plan.InformationNeedId;
import com.java.system.agent.answering.domain.plan.QuestionPlan;

import java.util.List;
import java.util.function.Function;

/**
 * 將問題解析輸入映射為只含依序資訊需求的未驗證計畫動作
 */
public final class PlanQuestionPlanningMapper implements Function<PlanQuestionPlanningInput, PlanAction> {

    private static final String QUESTION_PLAN_CONTRACT = "reason=QUESTION_PLAN_CONTRACT";

    @Override
    public PlanAction apply(PlanQuestionPlanningInput input) {
        try {
            List<InformationNeed> needs = input.needs().stream()
                    .map(need -> new InformationNeed(new InformationNeedId(need.id()), need.description()))
                    .toList();
            return new PlanAction(new QuestionPlan(needs));
        } catch (IllegalArgumentException exception) {
            throw PlanningToolInputException.safeDiagnostic(QUESTION_PLAN_CONTRACT);
        }
    }
}

package com.java.system.agent.slack.pipeline;

import com.java.system.agent.ai.service.AgentAiService;
import com.java.system.agent.slack.client.SlackStreamClient;
import com.java.system.agent.slack.client.SlackStreamFailureHandler;
import com.java.system.agent.slack.model.SlackMessageContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Agent-style Slack pipeline，使用 AgentAiService */
@Component
@Slf4j
public class SlackAgentPipeline {

    private final AgentAiService agentAiService;
    private final SlackStreamClient slackStreamClient;

    public SlackAgentPipeline(AgentAiService agentAiService, SlackStreamClient slackStreamClient) {
        this.agentAiService = agentAiService;
        this.slackStreamClient = slackStreamClient;
    }

    /** 執行 agent 分析並串流回覆，串流失敗時通知呼叫端 */
    public void execute(SlackMessageContext ctx, SlackStreamFailureHandler failureHandler) {
        slackStreamClient.consumeStream(
                ctx,
                "",
                () -> agentAiService.analyzeWithTools(ctx.getThreadTs(), ctx.getText()),
                failureHandler);
    }
}

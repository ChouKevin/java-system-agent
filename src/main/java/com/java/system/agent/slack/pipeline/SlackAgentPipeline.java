package com.java.system.agent.slack.pipeline;

import com.java.system.agent.ai.service.AgentAiService;
import com.java.system.agent.slack.client.SlackStreamClient;
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

    public void execute(SlackMessageContext ctx) {
        slackStreamClient.consumeStream(
                ctx,
                "",
                () -> agentAiService.analyzeWithTools(ctx.getThreadTs(), ctx.getText()));
    }
}

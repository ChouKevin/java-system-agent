package com.java.system.agent.slack.client;

import com.slack.api.methods.SlackApiTextResponse;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class SlackStreamResponse implements SlackApiTextResponse {
    private boolean ok;
    private String warning;
    private String error;
    private String needed;
    private String provided;
    private Map<String, List<String>> httpResponseHeaders;

    private String channel;
    private String ts;
    private Map<String, Object> message;
}

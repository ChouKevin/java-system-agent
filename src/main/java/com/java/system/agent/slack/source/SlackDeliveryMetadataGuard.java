package com.java.system.agent.slack.source;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.slack.api.bolt.middleware.Middleware;
import com.slack.api.bolt.middleware.MiddlewareChain;
import com.slack.api.bolt.request.Request;
import com.slack.api.bolt.response.Response;

/**
 * 在 typed event dispatch 前過濾本應用送出的 Slack delivery metadata
 */
public final class SlackDeliveryMetadataGuard implements Middleware {

    public static final String EVENT_TYPE = "java_system_agent_delivery_v1";

    @Override
    public Response apply(Request request, Response response, MiddlewareChain chain) throws Exception {
        if (isApplicationDelivery(request.getRequestBodyAsString())) {
            return request.getContext().ack();
        }
        return chain.next(request);
    }

    private static boolean isApplicationDelivery(String requestBody) {
        try {
            JsonElement document = JsonParser.parseString(requestBody);
            if (!document.isJsonObject()) {
                return false;
            }
            JsonObject event = document.getAsJsonObject().getAsJsonObject("event");
            if (!event.has("metadata") || !event.get("metadata").isJsonObject()) {
                return false;
            }
            JsonObject metadata = event.getAsJsonObject("metadata");
            return metadata.has("event_type")
                    && metadata.get("event_type").isJsonPrimitive()
                    && EVENT_TYPE.equals(metadata.get("event_type").getAsString());
        } catch (RuntimeException exception) {
            return false;
        }
    }
}

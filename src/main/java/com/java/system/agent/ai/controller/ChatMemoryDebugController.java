package com.java.system.agent.ai.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/debug/chat-memory")
@RequiredArgsConstructor
@Profile("dev|uat")
@Tag(name = "Chat Memory Debug", description = "View and manage in-memory chat history (dev only)")
class ChatMemoryDebugController {

    private final ChatMemory chatMemory;
    private final ChatMemoryRepository chatMemoryRepository;

    @GetMapping
    @Operation(summary = "List all conversation IDs (Slack threadTs)")
    public ResponseEntity<List<String>> listConversations() {
        return ResponseEntity.ok(chatMemoryRepository.findConversationIds());
    }

    @GetMapping("/{conversationId}")
    @Operation(summary = "Get chat history by conversation ID (Slack threadTs)")
    public ResponseEntity<List<MessageDto>> getMemory(@PathVariable String conversationId) {
        List<Message> messages = chatMemory.get(conversationId);
        List<MessageDto> result = messages.stream()
                .map(m -> new MessageDto(m.getMessageType().name(), m.getText()))
                .toList();
        return ResponseEntity.ok(result);
    }

    @DeleteMapping("/{conversationId}")
    @Operation(summary = "Clear chat history for a conversation")
    public ResponseEntity<Void> clearMemory(@PathVariable String conversationId) {
        chatMemory.clear(conversationId);
        return ResponseEntity.noContent().build();
    }

    record MessageDto(String role, String content) {
    }
}

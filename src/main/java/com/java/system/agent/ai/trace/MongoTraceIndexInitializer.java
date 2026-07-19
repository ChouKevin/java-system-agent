package com.java.system.agent.ai.trace;

import jakarta.annotation.PostConstruct;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexOperations;

import java.util.Objects;

/** 啟動時確保 agent_trace 查詢所需的 index 存在,重複執行不產生副作用 */
public class MongoTraceIndexInitializer {

    private final MongoTemplate mongoTemplate;

    public MongoTraceIndexInitializer(MongoTemplate mongoTemplate) {
        this.mongoTemplate = Objects.requireNonNull(mongoTemplate, "mongoTemplate must not be null");
    }

    @PostConstruct
    public void ensureIndexes() {
        IndexOperations indexOperations = mongoTemplate.indexOps(MongoAgentTraceStore.COLLECTION);
        indexOperations.createIndex(new Index()
                .on("conversationId", Sort.Direction.ASC)
                .on("createdAt", Sort.Direction.ASC));
        indexOperations.createIndex(new Index()
                .on("userId", Sort.Direction.ASC)
                .on("createdAt", Sort.Direction.DESC));
        indexOperations.createIndex(new Index()
                .on("eventId", Sort.Direction.ASC)
                .on("createdAt", Sort.Direction.DESC));
        indexOperations.createIndex(new Index()
                .on("accepted", Sort.Direction.ASC)
                .on("createdAt", Sort.Direction.DESC));
        indexOperations.createIndex(new Index()
                .on("createdAt", Sort.Direction.DESC));
    }
}

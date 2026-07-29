/**
 * 模型整合的模組邊界，消費 answering contracts 與統一 planning registry
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"answering :: domain", "answering :: port-out", "capability :: planning"})
package com.java.system.agent.model;

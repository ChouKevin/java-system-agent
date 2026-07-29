/**
 * 模型整合的模組邊界，消費 runtime contracts 與統一 planning registry
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"runtime :: domain", "runtime :: port-out", "capability :: planning"})
package com.java.system.agent.model;

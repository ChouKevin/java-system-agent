/**
 * 模型整合的模組邊界，消費 runtime 的 domain 與 outbound contracts
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"runtime :: domain", "runtime :: port-out", "capability :: tool-registry"})
package com.java.system.agent.model;

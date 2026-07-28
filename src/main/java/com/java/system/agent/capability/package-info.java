/**
 * Capability tool 與執行整合的模組邊界，消費 runtime 的 domain 與 outbound contracts
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"runtime :: domain", "runtime :: port-out"})
package com.java.system.agent.capability;

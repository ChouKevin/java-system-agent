/**
 * Durable session inbox 的 framework-free contract 與處理核心
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"runtime :: domain", "runtime :: port-in"})
package com.java.system.agent.inbox;

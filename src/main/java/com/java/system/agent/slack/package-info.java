/**
 * Slack Socket Mode 與 Web API 的 transport adapter
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"inbox :: domain", "inbox :: port-in", "inbox :: port-out", "runtime :: domain"})
package com.java.system.agent.slack;

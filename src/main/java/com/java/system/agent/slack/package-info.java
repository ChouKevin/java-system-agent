/**
 * Slack Socket Mode 與 Web API 的 transport adapter
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"interaction :: domain", "interaction :: port-in", "interaction :: port-out", "answering :: domain"})
package com.java.system.agent.slack;

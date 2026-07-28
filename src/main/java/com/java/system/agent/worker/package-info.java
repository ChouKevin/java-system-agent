/**
 * 以 inbox inbound contract 驅動的單機背景 worker lifecycle
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = "inbox :: port-in")
package com.java.system.agent.worker;

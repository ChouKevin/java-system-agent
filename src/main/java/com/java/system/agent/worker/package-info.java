/**
 * 以 interaction inbound contract 驅動的單機背景 worker lifecycle
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = "interaction :: port-in")
package com.java.system.agent.worker;

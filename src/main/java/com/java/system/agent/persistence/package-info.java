/**
 * PostgreSQL persistence adapters 的模組邊界
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"runtime :: domain", "runtime :: port-out", "inbox :: domain", "inbox :: port-out"})
package com.java.system.agent.persistence;

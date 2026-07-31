/**
 * PostgreSQL persistence adapters 的模組邊界
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {
                "answering :: domain",
                "answering :: port-out",
                "interaction :: domain",
                "interaction :: port-out"})
package com.java.system.agent.persistence;

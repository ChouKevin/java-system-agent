/**
 * Codebase 整合的模組邊界，透過 capability executor SPI 執行受控查詢
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"runtime :: domain", "runtime :: port-out", "capability :: executor-spi"})
package com.java.system.agent.codebase;

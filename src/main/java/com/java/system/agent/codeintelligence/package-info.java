/**
 * Code intelligence 整合的模組邊界，透過 capability executor SPI 與 planning contract 執行受控查詢
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {
                "answering :: domain",
                "answering :: port-out",
                "capability :: planning",
                "capability :: executor-spi"})
package com.java.system.agent.codeintelligence;

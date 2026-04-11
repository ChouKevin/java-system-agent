package com.java.system.agent.common.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class StartupConfigLogger implements CommandLineRunner {

    private final Environment environment;

    @Override
    public void run(String... args) {
        String appName = environment.getProperty("spring.application.name", "java-system-agent");
        String[] activeProfiles = environment.getActiveProfiles();
        String datasourceUrl = environment.getProperty("spring.datasource.url", "<not-configured>");
        String rabbitAddresses = environment.getProperty("spring.rabbitmq.addresses", "<not-configured>");
        String jacksonTimeZone = environment.getProperty("spring.jackson.time-zone", "<default>");

        String port = environment.getProperty("server.port", "8080");
        String contextPath = environment.getProperty("server.servlet.context-path", "");
        if (contextPath == null || contextPath.isBlank()) {
            contextPath = "";
        }

        String baseUrl = "http://localhost:" + port + contextPath;
        String swaggerUiUrl = baseUrl + "/swagger-ui/index.html";
        String openApiUrl = baseUrl + "/v3/api-docs";

        log.info("========================================================");
        log.info(" Application '{}' started", appName);
        log.info(" Active profiles        : {}", (Object) activeProfiles);
        log.info(" Datasource URL         : {}", datasourceUrl);
        log.info(" RabbitMQ addresses     : {}", rabbitAddresses);
        log.info(" Jackson time-zone      : {}", jacksonTimeZone);
        log.info(" JVM default TimeZone   : {}", java.util.TimeZone.getDefault().getID());
        log.info(" Swagger UI             : {}", swaggerUiUrl);
        log.info(" OpenAPI (v3) JSON      : {}", openApiUrl);
        log.info("========================================================");
    }
}

package com.java.system.agent;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import javax.sql.DataSource;

import java.util.Set;

import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import com.java.system.agent.persistence.jdbc.PostgresAgentOperationsAdapter;
import org.springframework.util.CollectionUtils;

/**
 * 在 agent-runtime profile 中建立 PostgreSQL persistence 基礎設施的根設定
 */
@Configuration(proxyBeanMethods = false)
@Profile("agent-runtime")
@EnableConfigurationProperties(AgentDatabaseProperties.class)
public final class AgentPersistenceConfiguration {

    @Bean
    @ConditionalOnMissingBean(DataSource.class)
    DataSource dataSource(AgentDatabaseProperties properties, Validator validator) {
        Set<ConstraintViolation<AgentDatabaseProperties>> validationFailures = validator.validate(properties);
        if (!CollectionUtils.isEmpty(validationFailures)) {
            throw new IllegalStateException(
                    "spring.datasource.url, spring.datasource.username, and spring.datasource.password "
                            + "must be configured when the agent runtime creates the default DataSource");
        }
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(properties.url());
        dataSource.setUsername(properties.username());
        dataSource.setPassword(properties.password());
        return dataSource;
    }

    @Bean
    @ConditionalOnMissingBean(JdbcClient.class)
    JdbcClient jdbcClient(DataSource dataSource, Flyway flyway) {
        return JdbcClient.create(dataSource);
    }

    @Bean
    @ConditionalOnMissingBean(TransactionTemplate.class)
    TransactionTemplate transactionTemplate(DataSource dataSource, Flyway flyway) {
        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
        return new TransactionTemplate(transactionManager);
    }

    @Bean
    @ConditionalOnMissingBean(PostgresAgentOperationsAdapter.class)
    PostgresAgentOperationsAdapter postgresAgentOperationsAdapter(JdbcClient jdbcClient) {
        return new PostgresAgentOperationsAdapter(jdbcClient);
    }

    @Bean(initMethod = "migrate")
    @ConditionalOnMissingBean(Flyway.class)
    Flyway flyway(DataSource dataSource) {
        return Flyway.configure().dataSource(dataSource).load();
    }
}

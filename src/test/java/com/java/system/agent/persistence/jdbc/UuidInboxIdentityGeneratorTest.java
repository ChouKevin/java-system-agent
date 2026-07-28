package com.java.system.agent.persistence.jdbc;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UuidInboxIdentityGenerator 配發各 durable identity 的驗證
 */
class UuidInboxIdentityGeneratorTest {

    @Test
    void allocatesOpaqueDeliveryAndConflictIdsAlongsideInboxIdentities() {
        UuidInboxIdentityGenerator generator = new UuidInboxIdentityGenerator();

        assertThat(generator.nextInboxMessageId().value()).isNotBlank();
        assertThat(generator.nextSessionId().value()).isNotBlank();
        assertThat(generator.nextRunId().value()).isNotBlank();
        assertThat(generator.nextDeliveryId()).isNotBlank();
        assertThat(generator.nextConflictId()).isNotBlank();
    }
}

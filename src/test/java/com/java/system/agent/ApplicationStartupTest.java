package com.java.system.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "spring.profiles.active=dev")
class ApplicationStartupTest {

    @Test
    @DisplayName("the application context loads")
    void should_load_application_context() {
    }
}

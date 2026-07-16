package com.java.system.agent.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * 註冊 API 寫入保護過濾器
 * <p>
 * 僅套用於 /git/* 路徑，安全方法（GET/HEAD/OPTIONS）由過濾器本身放行
 * 密鑰未設定時於啟動期印出 warning 並維持 fail-closed
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(ApiSecurityProperties.class)
public class ApiSecurityConfig {

    @Bean
    public FilterRegistrationBean<ApiWriteProtectionFilter> apiWriteProtectionFilterRegistration(
            ApiSecurityProperties properties, ObjectMapper objectMapper) {
        if (!properties.hasWriteToken()) {
            log.warn("api.write-token is not configured - all mutating /git/** requests "
                    + "will be rejected with 403; set API_WRITE_TOKEN in .env "
                    + "to enable git write endpoints");
        }
        FilterRegistrationBean<ApiWriteProtectionFilter> registration =
                new FilterRegistrationBean<>(
                        new ApiWriteProtectionFilter(properties, objectMapper));
        registration.addUrlPatterns("/git/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}

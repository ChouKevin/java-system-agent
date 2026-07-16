package com.java.system.agent.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 以共享密鑰保護會改變 repo 狀態的 /git 端點
 * <p>
 * 僅攔截非安全方法（GET/HEAD/OPTIONS 直接放行）
 * 密鑰未設定時採 fail-closed 一律回 403
 * 標頭缺失或密鑰不符時回 401
 * 錯誤 body 沿用 GlobalExceptionHandler.ErrorResponse 的格式
 */
@Slf4j
public class ApiWriteProtectionFilter extends OncePerRequestFilter {

    public static final String API_TOKEN_HEADER = "X-Api-Token";

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final ApiSecurityProperties properties;
    private final ObjectMapper objectMapper;

    public ApiWriteProtectionFilter(ApiSecurityProperties properties, ObjectMapper objectMapper) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return SAFE_METHODS.contains(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!properties.hasWriteToken()) {
            log.warn("Rejected {} {} - api.write-token is not configured; "
                            + "set API_WRITE_TOKEN in .env to enable git write endpoints",
                    request.getMethod(), request.getRequestURI());
            writeError(request, response, HttpStatus.FORBIDDEN,
                    "Git write endpoints are disabled",
                    "api.write-token is not configured; set API_WRITE_TOKEN in .env");
            return;
        }

        String provided = request.getHeader(API_TOKEN_HEADER);
        if (!properties.matchesWriteToken(provided)) {
            log.warn("Rejected {} {} - missing or invalid {} header",
                    request.getMethod(), request.getRequestURI(), API_TOKEN_HEADER);
            writeError(request, response, HttpStatus.UNAUTHORIZED,
                    "Invalid or missing API token",
                    API_TOKEN_HEADER + " header is required");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void writeError(HttpServletRequest request,
                            HttpServletResponse response,
                            HttpStatus status,
                            String message,
                            String detail) throws IOException {
        GlobalExceptionHandler.ErrorResponse body = new GlobalExceptionHandler.ErrorResponse(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                message,
                request.getRequestURI(),
                List.of(detail));
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}

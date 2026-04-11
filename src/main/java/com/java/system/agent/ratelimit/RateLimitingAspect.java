package com.java.system.agent.ratelimit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;

/** @RateLimit 方法的頻率限制 Aspect。 */
@Aspect
@Component
@Slf4j
@RequiredArgsConstructor
public class RateLimitingAspect {

    private final RateLimitingService rateLimitingService;

    @Before("@annotation(rateLimit)")
    public void checkRateLimit(JoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
        String key = extractKey(joinPoint);

        if (key != null) {
            if (!rateLimitingService.tryAcquire(key)) {
                log.warn("Rate limit exceeded for key: {}", key);

                String message = (rateLimit.message() != null && !rateLimit.message().isEmpty())
                        ? rateLimit.message()
                        : "Rate limit exceeded";

                throw new RateLimitExceededException(key, message);
            }
        } else {
            log.warn("RateLimit annotation used but no RateLimitKey found in method {}",
                    joinPoint.getSignature().toShortString());
        }
    }

    private String extractKey(JoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Object[] args = joinPoint.getArgs();
        Parameter[] parameters = signature.getMethod().getParameters();
        List<String> keys = new ArrayList<>();

        // 1. Check method parameters for @RateLimitKey
        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i].isAnnotationPresent(RateLimitKey.class)) {
                if (args[i] != null) {
                    keys.add(args[i].toString());
                }
            }
        }

        // 2. Check fields of argument objects for @RateLimitKey
        for (Object arg : args) {
            if (arg == null) continue;

            Field[] fields = arg.getClass().getDeclaredFields();
            for (Field field : fields) {
                if (field.isAnnotationPresent(RateLimitKey.class)) {
                    try {
                        field.setAccessible(true);
                        Object value = field.get(arg);
                        if (value != null) {
                            keys.add(value.toString());
                        }
                    } catch (IllegalAccessException e) {
                        log.error("Failed to access field annotated with @RateLimitKey", e);
                    }
                }
            }
        }

        return keys.isEmpty() ? null : String.join("-", keys);
    }
}

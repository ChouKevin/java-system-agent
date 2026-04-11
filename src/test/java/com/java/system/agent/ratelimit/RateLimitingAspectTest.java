package com.java.system.agent.ratelimit;

import com.java.system.agent.slack.model.SlackMessageContext;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class RateLimitingAspectTest {

    @Mock
    private RateLimitingService rateLimitingService;

    @Mock
    private JoinPoint joinPoint;

    @Mock
    private MethodSignature methodSignature;

    @Mock
    private RateLimit rateLimit;

    private RateLimitingAspect aspect;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        aspect = new RateLimitingAspect(rateLimitingService);
        when(rateLimit.message()).thenReturn("");
    }

    @Test
    void testCheckRateLimitExceeded() throws Throwable {
        String userId = "user-123";
        SlackMessageContext context = SlackMessageContext.builder()
                .userId(userId)
                .channelId("channel-1")
                .build();

        // Target method with @RateLimitKey on first parameter
        Method method = TestService.class.getMethod("serviceMethod", SlackMessageContext.class);
        when(methodSignature.getMethod()).thenReturn(method);
        when(joinPoint.getSignature()).thenReturn(methodSignature);
        when(joinPoint.getArgs()).thenReturn(new Object[]{context});
        when(rateLimit.message()).thenReturn("Limit reached");

        // Simulate rate limit exceeded
        when(rateLimitingService.tryAcquire(userId)).thenReturn(false);

        assertThrows(RateLimitExceededException.class, () -> aspect.checkRateLimit(joinPoint, rateLimit));
    }

    @Test
    void testCheckRateLimitAllowed() throws Throwable {
        String userId = "user-123";
        SlackMessageContext context = SlackMessageContext.builder()
                .userId(userId)
                .build();

        Method method = TestService.class.getMethod("serviceMethod", SlackMessageContext.class);
        when(methodSignature.getMethod()).thenReturn(method);
        when(joinPoint.getSignature()).thenReturn(methodSignature);
        when(joinPoint.getArgs()).thenReturn(new Object[]{context});

        // Simulate rate limit allowed
        when(rateLimitingService.tryAcquire(userId)).thenReturn(true);

        aspect.checkRateLimit(joinPoint, rateLimit);
    }

    @Test
    void testCheckRateLimitMultipleKeys() throws Throwable {
        String userId = "user-123";
        String eventId = "event-456";
        SlackMessageContext context = SlackMessageContext.builder()
                .userId(userId)
                .build();

        Method method = TestService.class.getMethod("multiKeyMethod", String.class, SlackMessageContext.class);
        when(methodSignature.getMethod()).thenReturn(method);
        when(joinPoint.getSignature()).thenReturn(methodSignature);
        when(joinPoint.getArgs()).thenReturn(new Object[]{eventId, context});

        // The expected key is "event-456-user-123" (parameter first, then field)
        String expectedKey = eventId + "-" + userId;
        when(rateLimitingService.tryAcquire(expectedKey)).thenReturn(true);

        aspect.checkRateLimit(joinPoint, rateLimit);

        verify(rateLimitingService).tryAcquire(expectedKey);
    }

    // Helper class for providing Method objects
    static class TestService {
        public void serviceMethod(SlackMessageContext context) {}

        public void multiKeyMethod(@RateLimitKey String eventId, SlackMessageContext context) {}
    }
}

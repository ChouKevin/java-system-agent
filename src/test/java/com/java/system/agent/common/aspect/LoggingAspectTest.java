package com.java.system.agent.common.aspect;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoggingAspectTest {

    private static final String SENSITIVE_ARG = "full-slack-message-with-llm-prompt";

    @Mock
    private ProceedingJoinPoint joinPoint;

    @Mock
    private Signature signature;

    private LoggingAspect aspect;
    private Logger aspectLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        aspect = new LoggingAspect();
        aspectLogger = (Logger) LoggerFactory.getLogger(LoggingAspect.class);
        appender = new ListAppender<>();
        appender.start();
        aspectLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        aspectLogger.detachAppender(appender);
        appender.stop();
    }

    private void stubFailingJoinPoint(Throwable toThrow) throws Throwable {
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getDeclaringTypeName()).thenReturn("com.java.system.agent.demo.DemoService");
        when(signature.getName()).thenReturn("process");
        when(joinPoint.proceed()).thenThrow(toThrow);
    }

    private List<ILoggingEvent> errorEvents() {
        return appender.list.stream()
                .filter(event -> Level.ERROR.equals(event.getLevel()))
                .toList();
    }

    @Test
    void should_log_error_with_stack_trace_when_method_throws() throws Throwable {
        stubFailingJoinPoint(new IllegalStateException("boom"));

        assertThatThrownBy(() -> aspect.logAround(joinPoint))
                .isInstanceOf(IllegalStateException.class);

        List<ILoggingEvent> errors = errorEvents();
        assertThat(errors).hasSize(1);
        assertThat(errors.getFirst().getThrowableProxy()).isNotNull();
        assertThat(errors.getFirst().getThrowableProxy().getMessage()).isEqualTo("boom");
    }

    @Test
    void should_not_log_arguments_when_illegal_argument_exception_thrown() throws Throwable {
        when(joinPoint.getArgs()).thenReturn(new Object[]{SENSITIVE_ARG});
        stubFailingJoinPoint(new IllegalArgumentException("bad input"));

        assertThatThrownBy(() -> aspect.logAround(joinPoint))
                .isInstanceOf(IllegalArgumentException.class);

        List<ILoggingEvent> errors = errorEvents();
        assertThat(errors).hasSize(1);
        assertThat(errors.getFirst().getFormattedMessage()).doesNotContain(SENSITIVE_ARG);
        assertThat(errors.getFirst().getThrowableProxy()).isNotNull();
    }
}

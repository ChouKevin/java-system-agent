package com.java.system.agent.common.aspect;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;
import org.springframework.util.StopWatch;

@Aspect
@Component
@Slf4j
public class LoggingAspect {

    @Pointcut("within(com.java.system.agent..*)"
            + " && !within(com.java.system.agent.common.aspect..*)"
            + " && !@within(org.springframework.boot.context.properties.ConfigurationProperties)")
    public void applicationPackagePointcut() {
    }

    @Around("applicationPackagePointcut()")
    public Object logAround(ProceedingJoinPoint joinPoint) throws Throwable {
        String className = joinPoint.getSignature().getDeclaringTypeName();
        String methodName = joinPoint.getSignature().getName();

        log.debug("Enter: {}.{}() with argument[s] = {}", className, methodName, joinPoint.getArgs());

        StopWatch stopWatch = new StopWatch();
        stopWatch.start();

        try {
            Object result = joinPoint.proceed();
            stopWatch.stop();
            log.debug("Exit: {}.{}() . Execution time: {} ms",
                    className, methodName, stopWatch.getTotalTimeMillis());
            return result;
        } catch (Throwable e) {
            stopWatch.stop();
            log.error("Exception in {}.{}()", className, methodName, e);
            throw e;
        }
    }
}

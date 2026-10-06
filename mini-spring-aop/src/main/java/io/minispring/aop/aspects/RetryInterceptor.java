package io.minispring.aop.aspects;

import io.minispring.aop.AopUtils;
import io.minispring.aop.MethodInterceptor;
import io.minispring.aop.MethodInvocation;

/** Implements {@link Retry} by calling {@link MethodInvocation#proceed()} again. */
public final class RetryInterceptor implements MethodInterceptor {

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Retry retry = AopUtils.findAnnotation(invocation.method(), invocation.targetClass(), Retry.class).orElseThrow();
        int attempts = Math.max(1, retry.maxAttempts());
        for (int attempt = 1; ; attempt++) {
            try {
                return invocation.proceed();
            } catch (Throwable failure) {
                if (attempt >= attempts || !isRetryable(failure, retry)) {
                    throw failure;
                }
                if (retry.delayMillis() > 0) {
                    Thread.sleep(retry.delayMillis());
                }
            }
        }
    }

    private static boolean isRetryable(Throwable failure, Retry retry) {
        for (Class<? extends Throwable> type : retry.on()) {
            if (type.isInstance(failure)) {
                return true;
            }
        }
        return false;
    }
}

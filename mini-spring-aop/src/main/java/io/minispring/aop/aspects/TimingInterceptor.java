package io.minispring.aop.aspects;

import io.minispring.aop.AopUtils;
import io.minispring.aop.MethodInterceptor;
import io.minispring.aop.MethodInvocation;

/** Implements {@link Timed}. */
public final class TimingInterceptor implements MethodInterceptor {

    private final MethodTimings timings;

    public TimingInterceptor(MethodTimings timings) {
        this.timings = timings;
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        String name = AopUtils.findAnnotation(invocation.method(), invocation.targetClass(), Timed.class)
                .map(Timed::value)
                .filter(value -> !value.isEmpty())
                .orElseGet(() -> invocation.targetClass().getSimpleName() + "." + invocation.method().getName());
        long start = System.nanoTime();
        boolean failed = true;
        try {
            Object result = invocation.proceed();
            failed = false;
            return result;
        } finally {
            timings.record(name, System.nanoTime() - start, failed);
        }
    }
}

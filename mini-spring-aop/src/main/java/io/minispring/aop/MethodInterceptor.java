package io.minispring.aop;

/**
 * Code that runs around a method call. An interceptor decides whether, when and how often the
 * call continues by invoking {@link MethodInvocation#proceed()}, and may replace its result or
 * its exception. Transactions, retries and timing are all expressed this way.
 */
@FunctionalInterface
public interface MethodInterceptor {

    Object invoke(MethodInvocation invocation) throws Throwable;
}

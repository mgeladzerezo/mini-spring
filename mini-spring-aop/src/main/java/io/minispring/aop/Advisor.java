package io.minispring.aop;

import java.lang.annotation.Annotation;

/**
 * A pointcut paired with the interceptor to apply where it matches. Declare one as a bean and the
 * {@link AutoProxyCreator} picks it up; nothing else is needed to add an aspect.
 *
 * @param pointcut    where to apply
 * @param interceptor what to apply
 * @param order       position in the chain; lower values wrap higher ones (run first on the way in,
 *                    last on the way out)
 */
public record Advisor(Pointcut pointcut, MethodInterceptor interceptor, int order) {

    /** Order of the built-in retry advisor: outermost, so each attempt gets fresh inner advice. */
    public static final int RETRY_ORDER = 100;
    /** Order of the built-in timing advisor. */
    public static final int TIMED_ORDER = 200;
    /** Order of the transaction advisor: inside retry and timing, around the business method. */
    public static final int TRANSACTION_ORDER = 300;
    /** Default for application advisors: closest to the business method. */
    public static final int DEFAULT_ORDER = 1000;

    /** An advisor for methods (or classes) annotated with {@code annotationType}. */
    public static Advisor forAnnotation(Class<? extends Annotation> annotationType, MethodInterceptor interceptor,
                                        int order) {
        return new Advisor(new AnnotationPointcut(annotationType, true), interceptor, order);
    }
}

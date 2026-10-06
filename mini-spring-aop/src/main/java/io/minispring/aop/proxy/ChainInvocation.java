package io.minispring.aop.proxy;

import io.minispring.aop.MethodInterceptor;
import io.minispring.aop.MethodInvocation;
import java.lang.reflect.Method;
import java.util.List;

/**
 * The interceptor chain as an immutable linked view: the invocation handed to interceptor
 * {@code i} knows that {@code proceed()} means "run interceptor {@code i + 1}".
 *
 * <p>A mutable cursor shared by the whole chain would be shorter, but then an interceptor that
 * proceeds twice (retry) would find the cursor already at the end and silently skip the
 * interceptors between itself and the target. Here proceeding is repeatable by construction.
 */
final class ChainInvocation implements MethodInvocation {

    /** The end of the chain: how to run the real method. */
    @FunctionalInterface
    interface Terminal {
        Object call(Object[] arguments) throws Throwable;
    }

    private final Object proxy;
    private final Object target;
    private final Method method;
    private final Class<?> targetClass;
    private final Object[] arguments;
    private final List<MethodInterceptor> interceptors;
    private final int position;
    private final Terminal terminal;

    private ChainInvocation(Object proxy, Object target, Method method, Class<?> targetClass, Object[] arguments,
                            List<MethodInterceptor> interceptors, int position, Terminal terminal) {
        this.proxy = proxy;
        this.target = target;
        this.method = method;
        this.targetClass = targetClass;
        this.arguments = arguments;
        this.interceptors = interceptors;
        this.position = position;
        this.terminal = terminal;
    }

    /** Runs the whole chain for one call. */
    static Object invoke(Object proxy, Object target, Method method, Class<?> targetClass, Object[] arguments,
                         List<MethodInterceptor> interceptors, Terminal terminal) throws Throwable {
        return new ChainInvocation(proxy, target, method, targetClass, arguments, interceptors, 0, terminal).proceed();
    }

    @Override
    public Object proceed() throws Throwable {
        if (position == interceptors.size()) {
            return terminal.call(arguments);
        }
        MethodInvocation next = new ChainInvocation(proxy, target, method, targetClass, arguments, interceptors,
                position + 1, terminal);
        return interceptors.get(position).invoke(next);
    }

    @Override
    public Method method() {
        return method;
    }

    @Override
    public Class<?> targetClass() {
        return targetClass;
    }

    @Override
    public Object target() {
        return target;
    }

    @Override
    public Object proxy() {
        return proxy;
    }

    @Override
    public Object[] arguments() {
        return arguments;
    }

    @Override
    public String toString() {
        return targetClass.getSimpleName() + "." + method.getName() + "() at interceptor " + position + "/"
                + interceptors.size();
    }
}

package io.minispring.aop.proxy;

import io.minispring.aop.MethodInterceptor;
import java.lang.reflect.Method;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.List;

/**
 * The Java side of a generated subclass proxy: maps the method index baked into the bytecode to
 * the method's interceptor chain, and ends the chain with a call back into the proxy's
 * {@code invokeSuper} switch.
 */
final class ChainDispatcher implements ProxyDispatcher {

    private final Class<?> targetClass;
    private final List<Method> methods;
    private final List<List<MethodInterceptor>> chains;

    ChainDispatcher(Class<?> targetClass, List<Method> methods, List<List<MethodInterceptor>> chains) {
        this.targetClass = targetClass;
        this.methods = methods;
        this.chains = chains;
    }

    @Override
    public Object dispatch(Object self, int methodIndex, Object[] arguments) throws Throwable {
        GeneratedProxy proxy = (GeneratedProxy) self;
        Method method = methods.get(methodIndex);
        try {
            return ChainInvocation.invoke(self, self, method, targetClass, arguments, chains.get(methodIndex),
                    finalArguments -> proxy.miniSpring$invokeSuper(methodIndex, finalArguments));
        } catch (RuntimeException | Error unchecked) {
            throw unchecked;
        } catch (Throwable checked) {
            // Bytecode may throw anything, but callers compiled against the method's signature are not
            // prepared for a checked exception it does not declare. Mirror java.lang.reflect.Proxy.
            for (Class<?> declared : method.getExceptionTypes()) {
                if (declared.isInstance(checked)) {
                    throw checked;
                }
            }
            throw new UndeclaredThrowableException(checked);
        }
    }
}

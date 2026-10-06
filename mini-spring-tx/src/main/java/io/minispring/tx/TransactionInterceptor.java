package io.minispring.tx;

import io.minispring.aop.AopUtils;
import io.minispring.aop.MethodInterceptor;
import io.minispring.aop.MethodInvocation;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** The {@link MethodInterceptor} behind {@link Transactional}: reads the attributes, delegates to the manager. */
public final class TransactionInterceptor implements MethodInterceptor {

    private final TransactionManager manager;
    private final Map<Method, TransactionDefinition> definitions = new ConcurrentHashMap<>();

    public TransactionInterceptor(TransactionManager manager) {
        this.manager = manager;
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        TransactionDefinition definition = definitions.computeIfAbsent(invocation.method(),
                method -> definitionFor(method, invocation.targetClass()));
        return manager.execute(definition, invocation::proceed);
    }

    private static TransactionDefinition definitionFor(Method method, Class<?> targetClass) {
        Transactional annotation = AopUtils.findAnnotation(method, targetClass, Transactional.class).orElseThrow();
        return TransactionDefinition.from(annotation, targetClass.getSimpleName() + "." + method.getName());
    }
}

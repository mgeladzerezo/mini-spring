package io.minispring.aop.proxy;

import io.minispring.aop.Advisor;
import io.minispring.aop.MethodInterceptor;
import io.minispring.aop.MethodInvocation;
import io.minispring.aop.Pointcut;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Test interceptor that writes down every call it sees and then lets it through. */
final class Recorder implements MethodInterceptor {

    /** Matches every method a subclass could override. */
    static final Pointcut OVERRIDABLE = (method, targetClass) -> {
        int modifiers = method.getModifiers();
        return !Modifier.isFinal(modifiers) && !Modifier.isStatic(modifiers) && !Modifier.isPrivate(modifiers);
    };

    final List<String> calls = new ArrayList<>();
    private final String prefix;

    Recorder() {
        this("");
    }

    Recorder(String prefix) {
        this.prefix = prefix;
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        calls.add(prefix + invocation.method().getName() + Arrays.deepToString(invocation.arguments()));
        return invocation.proceed();
    }

    Advisor everywhere() {
        return new Advisor(OVERRIDABLE, this, Advisor.DEFAULT_ORDER);
    }

    Advisor on(String... methodNames) {
        List<String> names = List.of(methodNames);
        return new Advisor((method, targetClass) -> names.contains(method.getName()), this, Advisor.DEFAULT_ORDER);
    }
}

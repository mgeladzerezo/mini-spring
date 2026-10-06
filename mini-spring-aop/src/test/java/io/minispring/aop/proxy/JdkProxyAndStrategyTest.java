package io.minispring.aop.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.minispring.aop.Advisor;
import io.minispring.aop.AopUtils;
import io.minispring.aop.MethodInterceptor;
import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Proxy;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Proves interface-based proxies, the rule that picks between the two strategies, annotation
 * lookup across the hierarchy, and the self-invocation difference between the strategies.
 */
class JdkProxyAndStrategyTest {

    @Target({ElementType.METHOD, ElementType.TYPE})
    @Retention(RetentionPolicy.RUNTIME)
    @interface Audited {
        String value() default "";
    }

    interface Ledger {
        String post(String entry) throws IOException;

        @Audited("from-interface")
        String annotatedOnInterface();

        String plain();

        String outer();

        String inner();
    }

    static class JdbcLedger implements Ledger {
        @Override
        @Audited("on-implementation")
        public String post(String entry) throws IOException {
            if (entry.isEmpty()) {
                throw new IOException("empty entry");
            }
            return "posted:" + entry;
        }

        @Override
        public String annotatedOnInterface() {
            return "ok";
        }

        @Override
        public String plain() {
            return "plain";
        }

        @Override
        public String outer() {
            return "outer+" + this.inner(); // self-invocation
        }

        @Override
        @Audited
        public String inner() {
            return "inner";
        }

        @Override
        public String toString() {
            return "JdbcLedger";
        }
    }

    private final Recorder recorder = new Recorder();
    private final Advisor audited = Advisor.forAnnotation(Audited.class, recorder, 0);

    // ---- JDK proxies --------------------------------------------------------------------

    @Test
    void choosesAJdkProxyWhenEveryAdvisedMethodBelongsToAnInterface() throws Exception {
        ProxyPlan plan = ProxyPlan.of(JdbcLedger.class, List.of(audited));
        Ledger ledger = (Ledger) plan.newJdkProxy(new JdbcLedger());

        assertEquals(ProxyPlan.Strategy.JDK, plan.strategy());
        assertTrue(Proxy.isProxyClass(ledger.getClass()));
        assertFalse(ledger instanceof JdbcLedger, "the proxy implements the interface, it is not the class");
        assertEquals("posted:rent", ledger.post("rent"));
        assertEquals(List.of("post[rent]"), recorder.calls);
    }

    @Test
    void findsAdviceDeclaredOnTheImplementationOrOnTheInterface() throws Exception {
        Ledger ledger = (Ledger) ProxyPlan.of(JdbcLedger.class, List.of(audited)).newJdkProxy(new JdbcLedger());

        ledger.post("x");
        ledger.annotatedOnInterface();
        ledger.plain();

        assertEquals(List.of("post[x]", "annotatedOnInterface[]"), recorder.calls, "plain() carries no annotation");
    }

    @Test
    void theInvocationReportsTheImplementationMethodSoItsAnnotationsAreVisible() throws Exception {
        MethodInterceptor readsAnnotation = invocation ->
                AopUtils.findAnnotation(invocation.method(), invocation.targetClass(), Audited.class).orElseThrow().value()
                        + "/" + invocation.method().getDeclaringClass().getSimpleName();
        Ledger ledger = (Ledger) ProxyPlan.of(JdbcLedger.class, List.of(Advisor.forAnnotation(Audited.class, readsAnnotation, 0)))
                .newJdkProxy(new JdbcLedger());

        assertEquals("on-implementation/JdbcLedger", ledger.post("x"));
        assertEquals("from-interface/JdbcLedger", ledger.annotatedOnInterface());
    }

    @Test
    void exceptionsCrossTheProxyUnwrapped() {
        Ledger ledger = (Ledger) ProxyPlan.of(JdbcLedger.class, List.of(audited)).newJdkProxy(new JdbcLedger());
        MethodInterceptor timeout = invocation -> {
            throw new TimeoutException("undeclared");
        };
        Ledger failing = (Ledger) ProxyPlan.of(JdbcLedger.class, List.of(Advisor.forAnnotation(Audited.class, timeout, 0)))
                .newJdkProxy(new JdbcLedger());

        assertEquals("empty entry", assertThrows(IOException.class, () -> ledger.post("")).getMessage());
        assertInstanceOf(TimeoutException.class,
                assertThrows(UndeclaredThrowableException.class, () -> failing.post("x")).getCause());
    }

    @Test
    void objectMethodsHaveProxyIdentitySemantics() {
        JdbcLedger target = new JdbcLedger();
        ProxyPlan plan = ProxyPlan.of(JdbcLedger.class, List.of(audited));
        Object first = plan.newJdkProxy(target);
        Object second = plan.newJdkProxy(target);

        assertEquals(first, first);
        assertNotEquals(first, second);
        assertEquals("JdbcLedger (JDK proxy)", first.toString());
        assertSame(JdbcLedger.class, AopUtils.userClass(first));
        assertSame(String.class, AopUtils.userClass("not a proxy"));
        assertTrue(recorder.calls.isEmpty());
    }

    // ---- the self-invocation pitfall -------------------------------------------------------

    @Test
    void selfInvocationBypassesAJdkProxyButNotASubclassProxy() {
        Ledger jdk = (Ledger) ProxyPlan.of(JdbcLedger.class, List.of(audited)).newJdkProxy(new JdbcLedger());
        assertEquals("outer+inner", jdk.outer());
        assertEquals(List.of(), recorder.calls,
                "inside the target 'this' is the target, so this.inner() never passes through the proxy");

        JdbcLedger subclass = ProxyPlan.subclassing(JdbcLedger.class, List.of(audited))
                .newSubclassProxy(JdbcLedger.class, new Class<?>[0]);
        assertEquals("outer+inner", subclass.outer());
        assertEquals(List.of("inner[]"), recorder.calls,
                "here 'this' is the proxy instance, so this.inner() dispatches to the generated override");
    }

    // ---- strategy selection ---------------------------------------------------------------

    static class LedgerWithExtras extends JdbcLedger {
        @Audited
        public String reconcile() {
            return "reconciled";
        }
    }

    @Audited
    static class ClassLevel implements Runnable {
        @Override
        public void run() {
        }

        public void extraPublic() {
        }

        void packagePrivateHelper() {
        }
    }

    static class Unadvised implements Runnable {
        @Override
        public void run() {
        }
    }

    @Test
    void switchesToASubclassProxyAsSoonAsOneAdvisedMethodIsNotOnAnInterface() {
        ProxyPlan plan = ProxyPlan.of(LedgerWithExtras.class, List.of(audited));

        assertEquals(ProxyPlan.Strategy.SUBCLASS, plan.strategy());
        LedgerWithExtras proxy = plan.newSubclassProxy(LedgerWithExtras.class, new Class<?>[0]);
        assertEquals("reconciled", proxy.reconcile());
        assertEquals(List.of("reconcile[]"), recorder.calls);
    }

    @Test
    void aClassLevelAnnotationAdvisesPublicMethodsOnly() {
        ProxyPlan plan = ProxyPlan.of(ClassLevel.class, List.of(audited));

        assertEquals(Set.of("run", "extraPublic"),
                plan.methods().stream().map(java.lang.reflect.Method::getName).collect(Collectors.toSet()));
        assertEquals(ProxyPlan.Strategy.SUBCLASS, plan.strategy(), "extraPublic() is not declared by Runnable");
    }

    @Test
    void noMatchingAdvisorMeansNoProxy() {
        ProxyPlan plan = ProxyPlan.of(Unadvised.class, List.of(audited));

        assertEquals(ProxyPlan.Strategy.NONE, plan.strategy());
        assertThrows(IllegalStateException.class, plan::proxyClass);
    }

    @Test
    void methodLevelAnnotationsOverrideClassLevelOnes() throws Exception {
        @Audited("class")
        class Local {
            @Audited("method")
            public void specific() {
            }

            public void general() {
            }
        }

        assertEquals("method", AopUtils.findAnnotation(Local.class.getMethod("specific"), Local.class, Audited.class)
                .orElseThrow().value());
        assertEquals("class", AopUtils.findAnnotation(Local.class.getMethod("general"), Local.class, Audited.class)
                .orElseThrow().value());
    }
}

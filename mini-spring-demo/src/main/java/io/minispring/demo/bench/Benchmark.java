package io.minispring.demo.bench;

import io.minispring.aop.Advisor;
import io.minispring.aop.MethodInterceptor;
import io.minispring.aop.proxy.ProxyPlan;
import io.minispring.core.context.ApplicationContext;
import io.minispring.demo.BankApplication;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

/**
 * A small hand-rolled benchmark: the cost of one method call made directly, through reflection, through a
 * JDK dynamic proxy and through a generated subclass proxy, each with one and three pass-through
 * interceptors; and the time to start the demo application's container.
 *
 * <p>It is not JMH: there is no fork isolation, and the numbers depend on the JIT's mood. Each case is
 * warmed up for a few million calls and measured in several rounds; the median round is reported. Use
 * the figures to compare the strategies with each other on one machine, not as absolute claims.
 *
 * <pre>
 * java -cp "mini-spring-demo/target/mini-spring-demo.jar;mini-spring-demo/target/lib/*" io.minispring.demo.bench.Benchmark
 * </pre>
 */
public final class Benchmark {

    public interface Calculator {
        int add(int left, int right);
    }

    public static class SimpleCalculator implements Calculator {
        @Override
        public int add(int left, int right) {
            return left + right;
        }
    }

    private static final int WARMUP = 3_000_000;
    private static final int ROUNDS = 7;
    private static final int CALLS = 5_000_000;
    private static volatile int sink;

    private interface Case {
        int call(int i);
    }

    public static void main(String[] args) throws Exception {
        Calculator target = new SimpleCalculator();
        Method add = Calculator.class.getMethod("add", int.class, int.class);
        add.setAccessible(true);
        MethodInterceptor passThrough = MethodInterceptorHolder.PASS;

        System.out.println("java " + System.getProperty("java.version") + ", " + Runtime.getRuntime().availableProcessors()
                + " logical CPUs, " + System.getProperty("os.name"));
        System.out.printf("%-44s %10s%n", "case (median of " + ROUNDS + " rounds of " + CALLS + " calls)", "ns/call");
        report("direct call", i -> target.add(i, 1));
        report("Method.invoke", i -> {
            try {
                return (Integer) add.invoke(target, i, 1);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        });
        for (int interceptors : new int[] {1, 3}) {
            List<Advisor> advisors = java.util.Collections.nCopies(interceptors, new Advisor((m, c) -> m.getName().equals("add"),
                    passThrough, Advisor.DEFAULT_ORDER));
            Calculator jdk = (Calculator) ProxyPlan.of(SimpleCalculator.class, advisors).newJdkProxy(new SimpleCalculator());
            report("JDK proxy, " + interceptors + " interceptor(s)", i -> jdk.add(i, 1));
            Calculator generated = ProxyPlan.subclassing(SimpleCalculator.class, advisors)
                    .newSubclassProxy(SimpleCalculator.class, new Class<?>[0]);
            report("generated subclass proxy, " + interceptors + " interceptor(s)", i -> generated.add(i, 1));
        }

        System.out.println();
        double[] startups = new double[7];
        for (int i = 0; i < startups.length; i++) {
            try (ApplicationContext context = ApplicationContext.builder().register(BankApplication.class)
                    .scan("io.minispring.demo").property("server.port", "0").property("server.host", "127.0.0.1")
                    .property("bank.db.url", "jdbc:h2:mem:bench" + i + ";DB_CLOSE_DELAY=-1").build()) {
                startups[i] = context.startupReport().total().toNanos() / 1e6;
            }
        }
        double[] sorted = startups.clone();
        Arrays.sort(sorted);
        System.out.printf("container startup of the demo (%d beans, includes H2 schema and HTTP bind): "
                + "first run %.0f ms (cold JVM), median of all %d runs %.0f ms%n", beanCount(), startups[0],
                startups.length, sorted[sorted.length / 2]);
    }

    private static int beanCount() {
        try (ApplicationContext context = ApplicationContext.builder().register(BankApplication.class)
                .scan("io.minispring.demo").property("server.port", "0").property("server.host", "127.0.0.1")
                .property("bank.db.url", "jdbc:h2:mem:count;DB_CLOSE_DELAY=-1").build()) {
            return context.startupReport().beans().size();
        }
    }

    private static void report(String name, Case benchmark) {
        int total = 0;
        for (int i = 0; i < WARMUP; i++) {
            total += benchmark.call(i);
        }
        double[] rounds = new double[ROUNDS];
        for (int round = 0; round < ROUNDS; round++) {
            long start = System.nanoTime();
            for (int i = 0; i < CALLS; i++) {
                total += benchmark.call(i);
            }
            rounds[round] = (System.nanoTime() - start) / (double) CALLS;
        }
        sink = total;
        Arrays.sort(rounds);
        System.out.printf("%-44s %10.1f%n", name, rounds[ROUNDS / 2]);
    }

    /** Separate holder so the lambda is one shared instance. */
    private static final class MethodInterceptorHolder {
        static final MethodInterceptor PASS = invocation -> invocation.proceed();
    }
}

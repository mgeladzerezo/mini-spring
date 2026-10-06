package io.minispring.aop.proxy;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.minispring.aop.Advisor;
import io.minispring.aop.AopUtils;
import io.minispring.aop.MethodInterceptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

/**
 * Proves the bytecode generator: constructors with arguments, every primitive and wide type,
 * arrays, varargs, generics, checked exceptions, non-public methods and calls through to super.
 * Each case compares the proxy's answer with what the plain class computes and checks that the
 * interceptor saw the call with correctly boxed arguments.
 */
class SubclassProxyTest {

    static class Base {
        String inherited(String value) {
            return "base:" + value;
        }

        protected long protectedTwice(long value) {
            return value * 2;
        }
    }

    interface Named {
        String name();

        default String describe() {
            return "named:" + name();
        }
    }

    interface Transformer<T> {
        T apply(T input);
    }

    static class Awkward extends Base implements Named, Transformer<String> {
        final List<String> constructionLog = new ArrayList<>();
        final String label;
        private int counter;

        Awkward(int a, long b, double c, String label, int[] more) throws IOException {
            if (label == null) {
                throw new IOException("label required");
            }
            this.label = label + ":" + a + ":" + b + ":" + c + ":" + more.length;
            constructionLog.add(name()); // a virtual call from the constructor must not reach an interceptor
        }

        Awkward() throws IOException {
            this(0, 0L, 0d, "default", new int[0]);
        }

        public boolean not(boolean value) {
            return !value;
        }

        public byte nextByte(byte value) {
            return (byte) (value + 1);
        }

        public char nextChar(char value) {
            return (char) (value + 1);
        }

        public short nextShort(short value) {
            return (short) (value + 1);
        }

        public int sum(int a, int b) {
            return a + b;
        }

        /** long and double take two local variable slots each; a wrong slot computation breaks this. */
        public long wide(long a, int b, double c, long d) {
            return a + b + (long) c + d;
        }

        public float half(float value) {
            return value / 2;
        }

        public double mix(double a, float b, long c, byte d, char e, short f, boolean g) {
            return a + b + c + d + e + f + (g ? 1 : 0);
        }

        public void sideEffect(StringBuilder target) {
            target.append("done");
        }

        public int[] reversed(int[] values) {
            int[] result = new int[values.length];
            for (int i = 0; i < values.length; i++) {
                result[i] = values[values.length - 1 - i];
            }
            return result;
        }

        public String[][] grid(String[]... rows) {
            return rows;
        }

        public <T extends Comparable<T>> T max(List<T> items) {
            return Collections.max(items);
        }

        public String read(String path) throws IOException {
            if (path.isEmpty()) {
                throw new FileNotFoundException("empty path");
            }
            return "read:" + path;
        }

        protected String protectedCall(String value) {
            return "protected:" + value;
        }

        String packagePrivate(String value) {
            return "package:" + value;
        }

        public String outer(String value) {
            return "outer(" + inner(value) + ")";
        }

        public String inner(String value) {
            return "inner:" + value;
        }

        public synchronized int increment() {
            return ++counter;
        }

        @Override
        public String name() {
            return "awkward";
        }

        @Override
        public String apply(String input) {
            return input.toUpperCase();
        }

        public final String finalMethod() {
            return "final";
        }

        public static String staticMethod() {
            return "static";
        }
    }

    private static final Class<?>[] FULL_CONSTRUCTOR = {int.class, long.class, double.class, String.class, int[].class};

    private final Recorder recorder = new Recorder();

    private Awkward proxy() {
        return ProxyPlan.subclassing(Awkward.class, List.of(recorder.everywhere()))
                .newSubclassProxy(Awkward.class, FULL_CONSTRUCTOR, 1, 2L, 3.5, "x", new int[]{9});
    }

    // ---- construction -------------------------------------------------------------------

    @Test
    void passesConstructorArgumentsOfEveryWidthToSuper() {
        Awkward proxy = proxy();

        assertEquals("x:1:2:3.5:1", proxy.label);
    }

    @Test
    void supportsEveryAccessibleConstructor() {
        Awkward proxy = ProxyPlan.subclassing(Awkward.class, List.of(recorder.everywhere()))
                .newSubclassProxy(Awkward.class, new Class<?>[0]);

        assertEquals("default:0:0:0.0:0", proxy.label);
    }

    @Test
    void constructorExceptionsPropagateUnchanged() {
        ProxyPlan plan = ProxyPlan.subclassing(Awkward.class, List.of(recorder.everywhere()));

        IOException error = assertThrows(IOException.class,
                () -> plan.newSubclassProxy(Awkward.class, FULL_CONSTRUCTOR, 1, 2L, 3.5, null, new int[0]));

        assertEquals("label required", error.getMessage());
    }

    @Test
    void callsMadeWhileTheSuperclassConstructorRunsAreNotIntercepted() {
        Awkward proxy = proxy();

        assertEquals(List.of("awkward"), proxy.constructionLog);
        assertTrue(recorder.calls.isEmpty(), "the dispatcher is installed only after construction");
    }

    @Test
    void anInstanceThatWasNeverActivatedBehavesLikeThePlainClass() throws Exception {
        Class<?> proxyClass = ProxyPlan.subclassing(Awkward.class, List.of(recorder.everywhere())).proxyClass();
        Awkward inactive = (Awkward) proxyClass.getDeclaredConstructor().newInstance();

        assertEquals(5, inactive.sum(2, 3));
        assertEquals("outer(inner:x)", inactive.outer("x"));
        assertTrue(recorder.calls.isEmpty());
    }

    // ---- signatures ---------------------------------------------------------------------

    @Test
    void boxesAndUnboxesEveryPrimitiveType() {
        Awkward proxy = proxy();

        assertFalse(proxy.not(true));
        assertEquals((byte) 8, proxy.nextByte((byte) 7));
        assertEquals('b', proxy.nextChar('a'));
        assertEquals((short) 301, proxy.nextShort((short) 300));
        assertEquals(5, proxy.sum(2, 3));
        assertEquals(1.25f, proxy.half(2.5f));
        assertEquals(List.of("not[true]", "nextByte[7]", "nextChar[a]", "nextShort[300]", "sum[2, 3]", "half[2.5]"),
                recorder.calls);
    }

    @Test
    void keepsTwoSlotArgumentsApartFromTheirNeighbours() {
        Awkward proxy = proxy();

        assertEquals(5_000_000_000L + 6 + 7 + 8_000_000_000L, proxy.wide(5_000_000_000L, 6, 7.9, 8_000_000_000L));
        assertEquals(1.5 + 2.5f + 3 + 4 + 'A' + 6 + 1, proxy.mix(1.5, 2.5f, 3L, (byte) 4, 'A', (short) 6, true));
        assertEquals(List.of("wide[5000000000, 6, 7.9, 8000000000]", "mix[1.5, 2.5, 3, 4, A, 6, true]"), recorder.calls);
    }

    @Test
    void handlesVoidArraysVarargsAndGenericMethods() {
        Awkward proxy = proxy();
        StringBuilder target = new StringBuilder();

        proxy.sideEffect(target);
        assertEquals("done", target.toString());
        assertArrayEquals(new int[]{3, 2, 1}, proxy.reversed(new int[]{1, 2, 3}));
        String[][] grid = proxy.grid(new String[]{"a", "b"}, new String[]{"c"});
        assertEquals("c", grid[1][0]);
        assertEquals("pear", proxy.max(List.of("apple", "pear", "fig")));
        assertEquals(42, proxy.max(List.of(7, 42, 13)));
        assertEquals("grid[[[a, b], [c]]]", recorder.calls.get(2));
    }

    @Test
    void overridesProtectedPackagePrivateInheritedAndDefaultMethods() {
        Awkward proxy = proxy();

        assertEquals("protected:x", proxy.protectedCall("x"));
        assertEquals("package:x", proxy.packagePrivate("x"));
        assertEquals("base:x", proxy.inherited("x"));
        assertEquals(10L, proxy.protectedTwice(5L));
        assertEquals("named:awkward", proxy.describe());
        assertEquals(List.of("protectedCall[x]", "packagePrivate[x]", "inherited[x]", "protectedTwice[5]",
                "describe[]", "name[]"), recorder.calls, "describe() is a default method; its call to name() is advised too");
    }

    @Test
    void aCallThroughAGenericInterfaceReachesTheOverrideViaTheBridgeMethodExactlyOnce() {
        Transformer<String> transformer = proxy();

        assertEquals("ABC", transformer.apply("abc"));
        assertEquals(List.of("apply[abc]"), recorder.calls);
    }

    @Test
    void synchronizedMethodsStillWork() {
        Awkward proxy = proxy();

        assertEquals(1, proxy.increment());
        assertEquals(2, proxy.increment());
    }

    @Test
    void leavesFinalAndStaticMethodsAloneWhenTheyAreNotAdvised() {
        Awkward proxy = proxy();

        assertEquals("final", proxy.finalMethod());
        assertEquals("static", Awkward.staticMethod());
        assertTrue(recorder.calls.isEmpty());
    }

    // ---- exceptions ---------------------------------------------------------------------

    @Test
    void declaredCheckedExceptionsFromTheTargetPassThrough() throws Exception {
        Awkward proxy = proxy();

        assertEquals("read:/etc/hosts", proxy.read("/etc/hosts"));
        FileNotFoundException error = assertThrows(FileNotFoundException.class, () -> proxy.read(""));
        assertEquals("empty path", error.getMessage());
    }

    @Test
    void anUndeclaredCheckedExceptionFromAnInterceptorIsWrappedLikeJdkProxiesDo() {
        MethodInterceptor timeout = invocation -> {
            throw new TimeoutException("too slow");
        };
        MethodInterceptor io = invocation -> {
            throw new IOException("declared by read()");
        };
        Awkward undeclared = ProxyPlan.subclassing(Awkward.class,
                        List.of(new Advisor(Recorder.OVERRIDABLE, timeout, 0)))
                .newSubclassProxy(Awkward.class, FULL_CONSTRUCTOR, 1, 2L, 3.5, "x", new int[0]);
        Awkward declared = ProxyPlan.subclassing(Awkward.class, List.of(new Advisor(Recorder.OVERRIDABLE, io, 0)))
                .newSubclassProxy(Awkward.class, FULL_CONSTRUCTOR, 1, 2L, 3.5, "x", new int[0]);

        UndeclaredThrowableException wrapped = assertThrows(UndeclaredThrowableException.class,
                () -> undeclared.sum(1, 2));
        assertInstanceOf(TimeoutException.class, wrapped.getCause());
        assertThrows(IOException.class, () -> declared.read("x"), "read() declares IOException, so no wrapping");
    }

    @Test
    void anInterceptorReturningNullForAPrimitiveFailsLoudly() {
        Awkward proxy = ProxyPlan.subclassing(Awkward.class,
                        List.of(new Advisor(Recorder.OVERRIDABLE, invocation -> null, 0)))
                .newSubclassProxy(Awkward.class, FULL_CONSTRUCTOR, 1, 2L, 3.5, "x", new int[0]);

        assertThrows(NullPointerException.class, () -> proxy.sum(1, 2));
        assertNull(proxy.inner("x"), "null is a legal result for a reference return type");
    }

    // ---- interceptor chain --------------------------------------------------------------

    @Test
    void interceptorsRunInAdvisorOrderAndCanChangeArgumentsAndResults() {
        List<String> trace = new ArrayList<>();
        MethodInterceptor outer = invocation -> {
            trace.add("outer>");
            invocation.arguments()[0] = "changed";
            Object result = invocation.proceed();
            trace.add("<outer");
            return result + "!";
        };
        MethodInterceptor inner = invocation -> {
            trace.add("inner>" + invocation.arguments()[0]);
            try {
                return invocation.proceed();
            } finally {
                trace.add("<inner");
            }
        };
        Awkward proxy = ProxyPlan.subclassing(Awkward.class, List.of(
                        new Advisor(Recorder.OVERRIDABLE, inner, 20), new Advisor(Recorder.OVERRIDABLE, outer, 10)))
                .newSubclassProxy(Awkward.class, FULL_CONSTRUCTOR, 1, 2L, 3.5, "x", new int[0]);

        assertEquals("inner:changed!", proxy.inner("original"));
        assertEquals(List.of("outer>", "inner>changed", "<inner", "<outer"), trace);
    }

    @Test
    void proceedingTwiceRunsTheRestOfTheChainTwice() {
        Recorder innerRecorder = new Recorder("inner:");
        MethodInterceptor twice = invocation -> {
            invocation.proceed();
            return invocation.proceed();
        };
        Awkward proxy = ProxyPlan.subclassing(Awkward.class, List.of(
                        new Advisor(Recorder.OVERRIDABLE, twice, 1), innerRecorder.on("increment")))
                .newSubclassProxy(Awkward.class, FULL_CONSTRUCTOR, 1, 2L, 3.5, "x", new int[0]);

        assertEquals(2, proxy.increment(), "the target ran twice");
        assertEquals(List.of("inner:increment[]", "inner:increment[]"), innerRecorder.calls,
                "and so did the interceptor after the repeating one");
    }

    @Test
    void anInterceptorMayAnswerWithoutCallingTheTarget() {
        Awkward proxy = ProxyPlan.subclassing(Awkward.class,
                        List.of(new Advisor((method, type) -> method.getName().equals("inner"), invocation -> "cached", 0)))
                .newSubclassProxy(Awkward.class, FULL_CONSTRUCTOR, 1, 2L, 3.5, "x", new int[0]);

        assertEquals("cached", proxy.inner("x"));
        assertEquals("outer(cached)", proxy.outer("x"), "self-invocation is virtual dispatch, so it is advised");
    }

    @Test
    void theInvocationExposesTheUserMethodAndClass() {
        List<Object> seen = new ArrayList<>();
        MethodInterceptor inspector = invocation -> {
            seen.add(invocation.method().getDeclaringClass());
            seen.add(invocation.targetClass());
            seen.add(invocation.proxy() == invocation.target());
            return invocation.proceed();
        };
        Awkward proxy = ProxyPlan.subclassing(Awkward.class,
                        List.of(new Advisor((method, type) -> method.getName().equals("inherited"), inspector, 0)))
                .newSubclassProxy(Awkward.class, FULL_CONSTRUCTOR, 1, 2L, 3.5, "x", new int[0]);

        proxy.inherited("x");

        assertEquals(List.of(Base.class, Awkward.class, true), seen);
    }

    // ---- shape of the generated class -----------------------------------------------------

    @Test
    void theGeneratedClassIsAHiddenFinalSubclassInTheSamePackage() throws Exception {
        Awkward proxy = proxy();
        Class<?> proxyClass = proxy.getClass();

        assertTrue(proxyClass.isHidden());
        assertTrue(Modifier.isFinal(proxyClass.getModifiers()));
        assertSame(Awkward.class, proxyClass.getSuperclass());
        assertEquals(Awkward.class.getPackageName(), proxyClass.getPackageName());
        assertTrue(proxyClass.getName().startsWith(Awkward.class.getName() + "$$MiniProxy"), proxyClass.getName());
        assertInstanceOf(GeneratedProxy.class, proxy);
        assertSame(Awkward.class, AopUtils.userClass(proxy));
        assertTrue(AopUtils.isProxy(proxy));

        Method read = proxyClass.getDeclaredMethod("read", String.class);
        assertArrayEquals(new Class<?>[]{IOException.class}, read.getExceptionTypes(), "throws clause is preserved");
        assertTrue(proxyClass.getDeclaredMethod("grid", String[][].class).isVarArgs());
        assertTrue(Modifier.isProtected(proxyClass.getDeclaredMethod("protectedCall", String.class).getModifiers()));
    }

    @Test
    void onlyAdvisedMethodsAreOverridden() {
        Awkward proxy = ProxyPlan.subclassing(Awkward.class, List.of(recorder.on("sum")))
                .newSubclassProxy(Awkward.class, FULL_CONSTRUCTOR, 1, 2L, 3.5, "x", new int[0]);

        List<String> declared = java.util.Arrays.stream(proxy.getClass().getDeclaredMethods())
                .map(Method::getName).sorted().toList();

        assertEquals(List.of("miniSpring$activate", "miniSpring$invokeSuper", "sum"), declared);
    }

    @Test
    void twoPlansForOneClassDefineTwoIndependentClasses() {
        Class<?> first = ProxyPlan.subclassing(Awkward.class, List.of(recorder.everywhere())).proxyClass();
        Class<?> second = ProxyPlan.subclassing(Awkward.class, List.of(recorder.on("sum"))).proxyClass();

        assertNotSame(first, second, "hidden classes do not collide on a name");
    }

    @Test
    void invokeSuperRejectsAnUnknownMethodIndex() {
        GeneratedProxy proxy = (GeneratedProxy) proxy();

        assertThrows(IllegalArgumentException.class, () -> proxy.miniSpring$invokeSuper(999, new Object[0]));
    }

    // ---- what cannot be proxied ------------------------------------------------------------

    static class WithFinalMethod {
        public final void pay(long cents) {
        }

        public void refund(long cents) {
        }

        private void audit() {
        }

        static void help() {
        }
    }

    static final class FinalClass {
        public void run() {
        }
    }

    record Point(int x, int y) {
        public int sum() {
            return x + y;
        }
    }

    static class OnlyPrivateConstructors {
        private OnlyPrivateConstructors() {
        }

        public void run() {
        }
    }

    @Test
    void reportsEveryAdvisedMemberThatCannotBeOverridden() {
        Advisor all = new Advisor((method, type) -> true, new Recorder(), 0);

        ProxyCreationException error = assertThrows(ProxyCreationException.class,
                () -> ProxyPlan.of(WithFinalMethod.class, List.of(all)));

        String message = error.getMessage();
        assertTrue(message.startsWith("Cannot create a subclass proxy for " + WithFinalMethod.class.getName()), message);
        assertTrue(message.contains("advised method 'void pay(long)' is final"), message);
        assertTrue(message.contains("advised method 'void audit()' is private"), message);
        assertTrue(message.contains("advised method 'void help()' is static"), message);
        assertFalse(message.contains("refund"), message);
    }

    @Test
    void aFinalMethodThatIsNotAdvisedIsNoObstacle() {
        ProxyPlan plan = ProxyPlan.of(WithFinalMethod.class, List.of(recorder.on("refund")));
        WithFinalMethod proxy = plan.newSubclassProxy(WithFinalMethod.class, new Class<?>[0]);

        proxy.pay(1);
        proxy.refund(2);

        assertEquals(List.of("refund[2]"), recorder.calls);
    }

    @Test
    void reportsFinalClassesRecordsAndUnreachableConstructors() {
        Advisor all = recorder.everywhere();

        assertTrue(assertThrows(ProxyCreationException.class, () -> ProxyPlan.of(FinalClass.class, List.of(all)))
                .getMessage().contains("the class is final"));
        assertTrue(assertThrows(ProxyCreationException.class, () -> ProxyPlan.of(Point.class, List.of(all)))
                .getMessage().contains("records and enums are final"));
        assertTrue(assertThrows(ProxyCreationException.class,
                () -> ProxyPlan.of(OnlyPrivateConstructors.class, List.of(all)))
                .getMessage().contains("all its constructors are private"));
        assertTrue(assertThrows(ProxyCreationException.class, () -> ProxyPlan.subclassing(ArrayList.class, List.of(all)))
                .getMessage().contains("JDK or hidden class"));
    }
}

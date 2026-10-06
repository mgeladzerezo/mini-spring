package io.minispring.aop.aspects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.minispring.aop.Advisor;
import io.minispring.aop.proxy.ProxyPlan;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Proves the two built-in aspects in isolation, without a container. */
class AspectsTest {

    static class Remote {
        int attempts;

        @Retry(maxAttempts = 4, on = IOException.class)
        public String fetch(int failuresBeforeSuccess) throws IOException {
            if (++attempts <= failuresBeforeSuccess) {
                throw new IOException("attempt " + attempts);
            }
            return "ok after " + attempts;
        }

        @Retry(maxAttempts = 4, on = IOException.class)
        public String wrongKind() {
            attempts++;
            throw new IllegalArgumentException("not retryable");
        }

        @Retry(maxAttempts = 2, delayMillis = 30)
        public String slowRetry() {
            if (++attempts < 2) {
                throw new IllegalStateException("first");
            }
            return "second";
        }

        @Timed
        public void fast() {
        }

        @Timed("custom.name")
        public void failing() {
            throw new IllegalStateException("boom");
        }
    }

    private final MethodTimings timings = new MethodTimings();

    private Remote proxy() {
        List<Advisor> advisors = List.of(
                Advisor.forAnnotation(Retry.class, new RetryInterceptor(), Advisor.RETRY_ORDER),
                Advisor.forAnnotation(Timed.class, new TimingInterceptor(timings), Advisor.TIMED_ORDER));
        return ProxyPlan.of(Remote.class, advisors).newSubclassProxy(Remote.class, new Class<?>[0]);
    }

    @Test
    void retriesUntilSuccessWithinTheAttemptLimit() throws IOException {
        Remote remote = proxy();

        assertEquals("ok after 3", remote.fetch(2));
    }

    @Test
    void givesUpAfterMaxAttemptsAndRethrowsTheLastFailure() {
        Remote remote = proxy();

        IOException error = assertThrows(IOException.class, () -> remote.fetch(10));

        assertEquals("attempt 4", error.getMessage());
        assertEquals(4, remote.attempts);
    }

    @Test
    void doesNotRetryExceptionsOutsideTheConfiguredTypes() {
        Remote remote = proxy();

        assertThrows(IllegalArgumentException.class, remote::wrongKind);
        assertEquals(1, remote.attempts);
    }

    @Test
    void waitsBetweenAttempts() {
        Remote remote = proxy();
        long start = System.nanoTime();

        assertEquals("second", remote.slowRetry());
        assertTrue(System.nanoTime() - start >= 25_000_000L, "one 30 ms pause was requested");
    }

    @Test
    void timedRecordsCountsFailuresAndDurationsUnderDefaultOrCustomNames() {
        Remote remote = proxy();

        remote.fast();
        remote.fast();
        assertThrows(IllegalStateException.class, remote::failing);

        Map<String, MethodTimings.Timing> snapshot = timings.snapshot();
        assertEquals(List.of("Remote.fast", "custom.name"), List.copyOf(snapshot.keySet()));
        assertEquals(2, snapshot.get("Remote.fast").count());
        assertEquals(0, snapshot.get("Remote.fast").failures());
        assertEquals(1, snapshot.get("custom.name").failures());
        assertTrue(snapshot.get("Remote.fast").maxNanos() <= snapshot.get("Remote.fast").totalNanos());
        assertTrue(snapshot.get("Remote.fast").meanMillis() >= 0);
    }
}

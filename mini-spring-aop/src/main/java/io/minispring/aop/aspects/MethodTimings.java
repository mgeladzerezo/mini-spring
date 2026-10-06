package io.minispring.aop.aspects;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Thread-safe store of what {@link Timed} methods measured. */
public final class MethodTimings {

    /**
     * An immutable view of one metric.
     *
     * @param count      completed calls, successful or not
     * @param failures   calls that ended with an exception
     * @param totalNanos sum of durations
     * @param maxNanos   longest single call
     */
    public record Timing(long count, long failures, long totalNanos, long maxNanos) {

        public double meanMillis() {
            return count == 0 ? 0 : totalNanos / 1_000_000.0 / count;
        }
    }

    private static final class Cell {
        private final LongAdder count = new LongAdder();
        private final LongAdder failures = new LongAdder();
        private final LongAdder totalNanos = new LongAdder();
        private final AtomicLong maxNanos = new AtomicLong();
    }

    private final Map<String, Cell> cells = new ConcurrentHashMap<>();

    void record(String name, long nanos, boolean failed) {
        Cell cell = cells.computeIfAbsent(name, key -> new Cell());
        cell.count.increment();
        cell.totalNanos.add(nanos);
        cell.maxNanos.accumulateAndGet(nanos, Math::max);
        if (failed) {
            cell.failures.increment();
        }
    }

    /** All metrics by name, sorted. */
    public Map<String, Timing> snapshot() {
        Map<String, Timing> snapshot = new TreeMap<>();
        cells.forEach((name, cell) -> snapshot.put(name,
                new Timing(cell.count.sum(), cell.failures.sum(), cell.totalNanos.sum(), cell.maxNanos.get())));
        return snapshot;
    }
}

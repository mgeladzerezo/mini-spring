package io.minispring.demo.service;

import io.minispring.core.annotation.Component;
import io.minispring.core.annotation.EventListener;
import io.minispring.demo.domain.TransferCompleted;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Keeps the last few completed transfers; fed by an {@code @EventListener}, never called by the service. */
@Component
public class AuditLog {

    public record Entry(Instant at, String message) {
    }

    private static final int CAPACITY = 50;
    private final Deque<Entry> entries = new ArrayDeque<>();

    @EventListener
    public synchronized void onTransfer(TransferCompleted event) {
        if (entries.size() == CAPACITY) {
            entries.removeFirst();
        }
        var transfer = event.transfer();
        entries.addLast(new Entry(Instant.now(), "transfer #" + transfer.id() + ": " + transfer.amount() + " from account "
                + transfer.fromId() + " to " + transfer.toId()));
    }

    /** Newest first. */
    public synchronized List<Entry> entries() {
        List<Entry> copy = new ArrayList<>(entries);
        java.util.Collections.reverse(copy);
        return copy;
    }
}

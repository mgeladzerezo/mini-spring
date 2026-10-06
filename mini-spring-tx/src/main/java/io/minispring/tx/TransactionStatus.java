package io.minispring.tx;

/**
 * Handle for one {@link TransactionManager#begin} call. It remembers whether this call owns the
 * physical transaction (and must therefore commit or roll it back) or merely joined one, and
 * which outer transaction to resume when a suspended one is waiting.
 */
public final class TransactionStatus {

    private final TransactionDefinition definition;
    private final ConnectionHolder holder;
    private final boolean newTransaction;
    private final ConnectionHolder suspended;
    private final int previousIsolation;
    private final boolean previousReadOnly;
    private boolean completed;

    TransactionStatus(TransactionDefinition definition, ConnectionHolder holder, boolean newTransaction,
                      ConnectionHolder suspended, int previousIsolation, boolean previousReadOnly) {
        this.definition = definition;
        this.holder = holder;
        this.newTransaction = newTransaction;
        this.suspended = suspended;
        this.previousIsolation = previousIsolation;
        this.previousReadOnly = previousReadOnly;
    }

    public TransactionDefinition definition() {
        return definition;
    }

    /** True if this call started the physical transaction. */
    public boolean isNewTransaction() {
        return newTransaction;
    }

    /** True if some transaction (this call's or a joined one) is running. */
    public boolean hasTransaction() {
        return holder != null;
    }

    public boolean isRollbackOnly() {
        return holder != null && holder.isRollbackOnly();
    }

    public boolean isCompleted() {
        return completed;
    }

    ConnectionHolder holder() {
        return holder;
    }

    ConnectionHolder suspended() {
        return suspended;
    }

    int previousIsolation() {
        return previousIsolation;
    }

    boolean previousReadOnly() {
        return previousReadOnly;
    }

    void complete() {
        completed = true;
    }
}

package io.minispring.tx;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;

/**
 * Begins, joins, suspends, commits and rolls back JDBC transactions, binding the connection to
 * the calling thread through {@link TransactionContext}.
 *
 * <p>The propagation decision table (what {@link #begin} does):
 * <pre>
 *                 transaction running                none running
 *  REQUIRED       join                               start new
 *  REQUIRES_NEW   suspend it, start new              start new
 *  SUPPORTS       join                               run without
 *  MANDATORY      join                               IllegalTransactionStateException
 *  NEVER          IllegalTransactionStateException   run without
 * </pre>
 *
 * <p>Joining shares one connection and one outcome: a participant that fails marks the
 * transaction rollback-only and only the method that started it touches the connection, which is
 * why a swallowed inner exception turns the outer commit into an {@link UnexpectedRollbackException}.
 * Suspension simply unbinds the outer holder and binds a second connection; the outer one is
 * rebound after the inner transaction ends. Two connections are in use at once, so a pool smaller
 * than the nesting depth can deadlock itself.
 */
public final class TransactionManager {

    /** The unit of work run by {@link #execute}. */
    @FunctionalInterface
    public interface TransactionCallback<T> {
        T run() throws Throwable;
    }

    private final DataSource dataSource;

    public TransactionManager(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public DataSource dataSource() {
        return dataSource;
    }

    /**
     * Runs {@code work} in a transaction as described by {@code definition} and decides commit or
     * rollback from the outcome. This is the single code path used by both {@code @Transactional}
     * and programmatic use.
     */
    public <T> T execute(TransactionDefinition definition, TransactionCallback<T> work) throws Throwable {
        TransactionStatus status = begin(definition);
        T result;
        try {
            result = work.run();
        } catch (Throwable failure) {
            try {
                if (definition.rollbackOn(failure)) {
                    rollback(status);
                } else {
                    commit(status);
                }
            } catch (RuntimeException | Error completionFailure) {
                failure.addSuppressed(completionFailure);
            }
            throw failure;
        }
        commit(status);
        return result;
    }

    public TransactionStatus begin(TransactionDefinition definition) {
        ConnectionHolder existing = TransactionContext.holder(dataSource);
        Propagation propagation = definition.propagation();
        if (existing != null) {
            return switch (propagation) {
                case NEVER -> throw new IllegalTransactionStateException("'" + definition.name()
                        + "' is NEVER transactional but runs inside the transaction of '" + existing.owner() + "'");
                case REQUIRES_NEW -> {
                    TransactionContext.unbind(dataSource);
                    try {
                        yield start(definition, existing);
                    } catch (RuntimeException | Error e) {
                        TransactionContext.bind(dataSource, existing);
                        throw e;
                    }
                }
                case REQUIRED, SUPPORTS, MANDATORY -> new TransactionStatus(definition, existing, false, null, 0, false);
            };
        }
        return switch (propagation) {
            case MANDATORY -> throw new IllegalTransactionStateException("'" + definition.name()
                    + "' is MANDATORY but no transaction is running");
            case REQUIRED, REQUIRES_NEW -> start(definition, null);
            case SUPPORTS, NEVER -> new TransactionStatus(definition, null, false, null, 0, false);
        };
    }

    private TransactionStatus start(TransactionDefinition definition, ConnectionHolder suspended) {
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            int previousIsolation = connection.getTransactionIsolation();
            boolean previousReadOnly = connection.isReadOnly();
            if (definition.isolation() != Isolation.DEFAULT) {
                connection.setTransactionIsolation(definition.isolation().jdbcLevel());
            }
            if (definition.readOnly()) {
                connection.setReadOnly(true);
            }
            connection.setAutoCommit(false);
            ConnectionHolder holder = new ConnectionHolder(connection, definition.name());
            TransactionContext.bind(dataSource, holder);
            return new TransactionStatus(definition, holder, true, suspended, previousIsolation, previousReadOnly);
        } catch (SQLException e) {
            closeQuietly(connection);
            throw new TransactionSystemException("Could not begin transaction for '" + definition.name() + "'", e);
        }
    }

    public void commit(TransactionStatus status) {
        requireOpen(status);
        if (!status.isNewTransaction()) {
            status.complete(); // joined or empty: the owner decides
            return;
        }
        ConnectionHolder holder = status.holder();
        if (holder.isRollbackOnly()) {
            rollback(status);
            throw new UnexpectedRollbackException("Transaction '" + holder.owner() + "' was rolled back because a "
                    + "participating method had marked it rollback-only (an exception that triggers rollback "
                    + "was thrown inside it and swallowed by its caller)");
        }
        try {
            holder.connection().commit();
        } catch (SQLException e) {
            try {
                holder.connection().rollback();
            } catch (SQLException rollbackFailure) {
                e.addSuppressed(rollbackFailure);
            }
            throw new TransactionSystemException("Commit of '" + holder.owner() + "' failed", e);
        } finally {
            finish(status);
        }
    }

    public void rollback(TransactionStatus status) {
        requireOpen(status);
        if (!status.isNewTransaction()) {
            if (status.hasTransaction()) {
                status.holder().markRollbackOnly();
            }
            status.complete();
            return;
        }
        try {
            status.holder().connection().rollback();
        } catch (SQLException e) {
            throw new TransactionSystemException("Rollback of '" + status.holder().owner() + "' failed", e);
        } finally {
            finish(status);
        }
    }

    private static void requireOpen(TransactionStatus status) {
        if (status.isCompleted()) {
            throw new IllegalStateException("Transaction '" + status.definition().name() + "' is already completed");
        }
    }

    /** Releases the connection, restores its settings for the pool and resumes a suspended transaction. */
    private void finish(TransactionStatus status) {
        status.complete();
        TransactionContext.unbind(dataSource);
        Connection connection = status.holder().connection();
        try {
            connection.setAutoCommit(true);
            connection.setReadOnly(status.previousReadOnly());
            if (status.definition().isolation() != Isolation.DEFAULT) {
                connection.setTransactionIsolation(status.previousIsolation());
            }
        } catch (SQLException ignored) {
            // the connection is being returned anyway; a pool validates it before reuse
        } finally {
            closeQuietly(connection);
            if (status.suspended() != null) {
                TransactionContext.bind(dataSource, status.suspended());
            }
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // nothing to do about a failing close
            }
        }
    }
}

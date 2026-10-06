package io.minispring.tx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.util.List;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every propagation mode against a real H2 database, in every situation that distinguishes them:
 * with and without a running outer transaction, an inner method that succeeds or fails, an outer
 * method that swallows the inner failure or not. Expectations are written out by hand, row by
 * row, rather than derived from the implementation's own decision logic.
 *
 * <p>The scenario: the outer method inserts "outer", then calls an inner method that inserts
 * "inner" and (optionally) throws. What ends up committed is read from a separate connection.
 */
class PropagationMatrixTest {

    private final DataSource dataSource = H2Support.newDatabase();
    private final TransactionManager manager = new TransactionManager(dataSource);
    private final JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    private Connection outerConnection;
    private Connection innerConnection;
    private boolean innerBodyRan;
    private boolean innerInTransaction;

    @AfterEach
    void noTransactionLeaksOntoTheThread() {
        assertFalse(TransactionContext.isActive(dataSource), "the thread must be clean after every scenario");
    }

    enum Outer {
        /** No transaction around the outer method. */
        NONE,
        /** Transaction; inner failure is caught and the outer method carries on and commits. */
        SWALLOWS,
        /** Transaction; inner failure propagates out of the outer method. */
        PROPAGATES,
        /** Transaction; inner succeeds but the outer method fails afterwards. */
        FAILS_AFTERWARDS
    }

    record Scenario(Outer outer, Propagation inner, boolean innerFails, List<String> committed,
                    Class<? extends Throwable> thrown, Boolean sameConnection, boolean innerBodyRuns,
                    boolean innerTransactional) {
    }

    static Scenario s(Outer outer, Propagation inner, boolean innerFails, List<String> committed,
                      Class<? extends Throwable> thrown, Boolean sameConnection, boolean innerBodyRuns,
                      boolean innerTransactional) {
        return new Scenario(outer, inner, innerFails, committed, thrown, sameConnection, innerBodyRuns,
                innerTransactional);
    }

    private static final Class<RuntimeException> BOOM = RuntimeException.class;
    private static final Class<UnexpectedRollbackException> UNEXPECTED = UnexpectedRollbackException.class;
    private static final Class<IllegalTransactionStateException> ILLEGAL = IllegalTransactionStateException.class;

    static Stream<Scenario> scenarios() {
        List<String> both = List.of("outer", "inner");
        List<String> none = List.of();
        List<String> outerOnly = List.of("outer");
        List<String> innerOnly = List.of("inner");
        return Stream.of(
                // ---- a transaction is running, inner succeeds, outer commits -------------------------
                s(Outer.SWALLOWS, Propagation.REQUIRED, false, both, null, true, true, true),
                s(Outer.SWALLOWS, Propagation.REQUIRES_NEW, false, both, null, false, true, true),
                s(Outer.SWALLOWS, Propagation.SUPPORTS, false, both, null, true, true, true),
                s(Outer.SWALLOWS, Propagation.MANDATORY, false, both, null, true, true, true),
                s(Outer.SWALLOWS, Propagation.NEVER, false, outerOnly, null, null, false, false),

                // ---- inner fails, outer catches it and tries to commit ---------------------------------
                // joined: the shared transaction is rollback-only, so the outer commit becomes a rollback
                s(Outer.SWALLOWS, Propagation.REQUIRED, true, none, UNEXPECTED, true, true, true),
                s(Outer.SWALLOWS, Propagation.SUPPORTS, true, none, UNEXPECTED, true, true, true),
                s(Outer.SWALLOWS, Propagation.MANDATORY, true, none, UNEXPECTED, true, true, true),
                // separate transaction: only the inner work is lost
                s(Outer.SWALLOWS, Propagation.REQUIRES_NEW, true, outerOnly, null, false, true, true),
                // refused before the body ran: nothing was marked, the outer transaction is intact
                s(Outer.SWALLOWS, Propagation.NEVER, true, outerOnly, null, null, false, false),

                // ---- inner fails and the exception reaches the outer boundary --------------------------
                s(Outer.PROPAGATES, Propagation.REQUIRED, true, none, BOOM, true, true, true),
                s(Outer.PROPAGATES, Propagation.REQUIRES_NEW, true, none, BOOM, false, true, true),
                s(Outer.PROPAGATES, Propagation.SUPPORTS, true, none, BOOM, true, true, true),
                s(Outer.PROPAGATES, Propagation.MANDATORY, true, none, BOOM, true, true, true),
                s(Outer.PROPAGATES, Propagation.NEVER, true, none, ILLEGAL, null, false, false),

                // ---- inner succeeds, outer fails afterwards ---------------------------------------------
                s(Outer.FAILS_AFTERWARDS, Propagation.REQUIRED, false, none, BOOM, true, true, true),
                // the inner transaction already committed independently
                s(Outer.FAILS_AFTERWARDS, Propagation.REQUIRES_NEW, false, innerOnly, BOOM, false, true, true),
                s(Outer.FAILS_AFTERWARDS, Propagation.SUPPORTS, false, none, BOOM, true, true, true),
                s(Outer.FAILS_AFTERWARDS, Propagation.MANDATORY, false, none, BOOM, true, true, true),

                // ---- no outer transaction ---------------------------------------------------------------
                // (the outer "insert" then runs in auto-commit and is always committed)
                s(Outer.NONE, Propagation.REQUIRED, false, both, null, null, true, true),
                s(Outer.NONE, Propagation.REQUIRES_NEW, false, both, null, null, true, true),
                s(Outer.NONE, Propagation.SUPPORTS, false, both, null, null, true, false),
                s(Outer.NONE, Propagation.MANDATORY, false, outerOnly, ILLEGAL, null, false, false),
                s(Outer.NONE, Propagation.NEVER, false, both, null, null, true, false),
                s(Outer.NONE, Propagation.REQUIRED, true, outerOnly, BOOM, null, true, true),
                s(Outer.NONE, Propagation.REQUIRES_NEW, true, outerOnly, BOOM, null, true, true),
                // without a transaction there is nothing to roll back: the row stays
                s(Outer.NONE, Propagation.SUPPORTS, true, both, BOOM, null, true, false),
                s(Outer.NONE, Propagation.MANDATORY, true, outerOnly, ILLEGAL, null, false, false),
                s(Outer.NONE, Propagation.NEVER, true, both, BOOM, null, true, false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void propagationBehavesAsDocumented(Scenario scenario) throws Throwable {
        Throwable thrown = null;
        try {
            runOuter(scenario);
        } catch (Throwable t) {
            thrown = t;
        }

        if (scenario.thrown() == null) {
            assertNull(thrown, "no exception expected");
        } else {
            assertNotNull(thrown, "expected " + scenario.thrown().getSimpleName());
            assertEquals(scenario.thrown(), thrown.getClass());
        }
        assertEquals(scenario.committed(), H2Support.committedTags(dataSource), "committed rows");
        assertEquals(scenario.innerBodyRuns(), innerBodyRan, "inner body ran");
        if (scenario.innerBodyRuns()) {
            assertEquals(scenario.innerTransactional(), innerInTransaction, "inner ran inside a transaction");
        }
        if (scenario.sameConnection() != null) {
            if (scenario.sameConnection()) {
                assertSame(outerConnection, innerConnection, "inner must share the outer connection");
            } else {
                assertNotNull(innerConnection);
                assertTrue(outerConnection != innerConnection, "inner must use a connection of its own");
            }
        }
    }

    private void runOuter(Scenario scenario) throws Throwable {
        TransactionDefinition outerDefinition = TransactionDefinition.of(Propagation.REQUIRED);
        TransactionDefinition innerDefinition = TransactionDefinition.of(scenario.inner());
        if (scenario.outer() == Outer.NONE) {
            jdbc.update("insert into log(tag) values (?)", "outer");
            runInner(innerDefinition, scenario.innerFails());
            return;
        }
        manager.execute(outerDefinition, () -> {
            jdbc.update("insert into log(tag) values (?)", "outer");
            outerConnection = TransactionContext.connection(dataSource);
            try {
                runInner(innerDefinition, scenario.innerFails());
            } catch (RuntimeException e) {
                if (scenario.outer() == Outer.PROPAGATES) {
                    throw e;
                }
            }
            if (scenario.outer() == Outer.FAILS_AFTERWARDS) {
                throw new RuntimeException("outer failed");
            }
            return null;
        });
    }

    private void runInner(TransactionDefinition definition, boolean fail) throws Throwable {
        manager.execute(definition, () -> {
            innerBodyRan = true;
            innerInTransaction = TransactionContext.isActive(dataSource);
            if (innerInTransaction) {
                innerConnection = TransactionContext.connection(dataSource);
            }
            jdbc.update("insert into log(tag) values (?)", "inner");
            if (fail) {
                throw new RuntimeException("inner failed");
            }
            return null;
        });
    }

    // ---- behaviour the matrix does not show --------------------------------------------------------------------

    @Test
    void theOuterTransactionIsResumedAfterARequiresNewTransactionEnds() throws Throwable {
        manager.execute(TransactionDefinition.of(Propagation.REQUIRED), () -> {
            Connection before = TransactionContext.connection(dataSource);
            manager.execute(TransactionDefinition.of(Propagation.REQUIRES_NEW), () -> {
                assertTrue(TransactionContext.connection(dataSource) != before);
                return null;
            });
            assertSame(before, TransactionContext.connection(dataSource), "the suspended transaction is bound again");
            jdbc.update("insert into log(tag) values (?)", "after");
            return null;
        });
        assertEquals(List.of("after"), H2Support.committedTags(dataSource));
    }

    @Test
    void anUncommittedRowIsInvisibleToOtherConnectionsUntilTheOutermostCommit() throws Throwable {
        manager.execute(TransactionDefinition.of(Propagation.REQUIRED), () -> {
            jdbc.update("insert into log(tag) values (?)", "pending");
            manager.execute(TransactionDefinition.of(Propagation.REQUIRED), () -> null); // inner "commit" is a no-op
            assertEquals(List.of(), H2Support.committedTags(dataSource));
            return null;
        });
        assertEquals(List.of("pending"), H2Support.committedTags(dataSource));
    }

    @Test
    void failingToBeginRestoresTheSuspendedTransaction() throws Throwable {
        DataSource failing = new FailingSecondConnection(dataSource);
        TransactionManager flaky = new TransactionManager(failing);
        flaky.execute(TransactionDefinition.of(Propagation.REQUIRED), () -> {
            Connection outer = TransactionContext.connection(failing);
            assertThrows(TransactionSystemException.class,
                    () -> flaky.execute(TransactionDefinition.of(Propagation.REQUIRES_NEW), () -> null));
            assertSame(outer, TransactionContext.connection(failing), "outer transaction still bound");
            return null;
        });
    }

    /** A data source whose second connection request fails, to exercise the error path of REQUIRES_NEW. */
    private static final class FailingSecondConnection implements DataSource {
        private final DataSource delegate;
        private int requests;

        FailingSecondConnection(DataSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public Connection getConnection() throws java.sql.SQLException {
            if (++requests == 2) {
                throw new java.sql.SQLException("pool exhausted");
            }
            return delegate.getConnection();
        }

        @Override
        public Connection getConnection(String username, String password) throws java.sql.SQLException {
            return getConnection();
        }

        @Override
        public java.io.PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(java.io.PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public java.util.logging.Logger getParentLogger() {
            return java.util.logging.Logger.getGlobal();
        }

        @Override
        public <T> T unwrap(Class<T> type) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isWrapperFor(Class<?> type) {
            return false;
        }
    }
}

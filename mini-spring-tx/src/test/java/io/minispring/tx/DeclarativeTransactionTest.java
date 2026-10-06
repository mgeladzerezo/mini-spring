package io.minispring.tx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.minispring.aop.proxy.GeneratedProxy;
import io.minispring.core.annotation.Bean;
import io.minispring.core.annotation.Component;
import io.minispring.core.annotation.Configuration;
import io.minispring.core.context.ApplicationContext;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@code @Transactional} end to end: container, proxies, interceptor, manager and H2. */
class DeclarativeTransactionTest {

    static class BusinessException extends Exception {
        BusinessException(String message) {
            super(message);
        }
    }

    static class SpecificBusinessException extends BusinessException {
        SpecificBusinessException(String message) {
            super(message);
        }
    }

    @Configuration
    @EnableTransactionManagement
    static class Config {
        @Bean
        DataSource dataSource() {
            return H2Support.newDatabase();
        }
    }

    // ---- an interface-based service: JDK proxy ---------------------------------------------------------

    interface Notes {
        void save(String tag);

        void saveThenFail(String tag);

        void saveThenFailChecked(String tag) throws BusinessException;

        void saveThenFailCheckedWithRollbackFor(String tag) throws BusinessException;

        void saveThenFailSpecific(String tag) throws BusinessException;

        void saveThenFailUncheckedButExcluded(String tag);

        boolean runsInTransaction();

        boolean callInnerViaThis();

        void nested(String tag);

        String connectionSettings();
    }

    @Component
    static class NotesImpl implements Notes {
        private final JdbcTemplate jdbc;
        private final DataSource dataSource;

        NotesImpl(JdbcTemplate jdbc, DataSource dataSource) {
            this.jdbc = jdbc;
            this.dataSource = dataSource;
        }

        @Override
        @Transactional
        public void save(String tag) {
            jdbc.update("insert into log(tag) values (?)", tag);
        }

        @Override
        @Transactional
        public void saveThenFail(String tag) {
            save(tag);
            throw new IllegalStateException("boom");
        }

        @Override
        @Transactional
        public void saveThenFailChecked(String tag) throws BusinessException {
            save(tag);
            throw new BusinessException("checked exceptions commit by default");
        }

        @Override
        @Transactional(rollbackFor = Exception.class)
        public void saveThenFailCheckedWithRollbackFor(String tag) throws BusinessException {
            save(tag);
            throw new BusinessException("rolled back because of rollbackFor");
        }

        @Override
        @Transactional(rollbackFor = Exception.class, noRollbackFor = SpecificBusinessException.class)
        public void saveThenFailSpecific(String tag) throws BusinessException {
            save(tag);
            throw new SpecificBusinessException("the closer noRollbackFor rule wins");
        }

        @Override
        @Transactional(noRollbackFor = IllegalStateException.class)
        public void saveThenFailUncheckedButExcluded(String tag) {
            save(tag);
            throw new IllegalStateException("committed despite being unchecked");
        }

        @Override
        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public boolean runsInTransaction() {
            return TransactionContext.isActive(dataSource);
        }

        /** Not transactional itself; calls a transactional method on {@code this}. */
        @Override
        public boolean callInnerViaThis() {
            return runsInTransaction();
        }

        @Override
        @Transactional
        public void nested(String tag) {
            save(tag);
        }

        @Override
        @Transactional(readOnly = true, isolation = Isolation.SERIALIZABLE)
        public String connectionSettings() {
            try {
                Connection connection = TransactionContext.connection(dataSource);
                return "readOnly=" + connection.isReadOnly() + " isolation=" + connection.getTransactionIsolation()
                        + " autoCommit=" + connection.getAutoCommit();
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    // ---- a class without interfaces: generated subclass proxy ------------------------------------------------

    @Component
    @Transactional
    static class ClassNotes {
        private final JdbcTemplate jdbc;
        private final DataSource dataSource;

        ClassNotes(JdbcTemplate jdbc, DataSource dataSource) {
            this.jdbc = jdbc;
            this.dataSource = dataSource;
        }

        public void saveThenFail(String tag) {
            jdbc.update("insert into log(tag) values (?)", tag);
            throw new IllegalStateException("boom");
        }

        public void save(String tag) {
            jdbc.update("insert into log(tag) values (?)", tag);
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public boolean runsInTransaction() {
            return TransactionContext.isActive(dataSource);
        }

        @Transactional(propagation = Propagation.SUPPORTS)
        public boolean callInnerViaThis() {
            return runsInTransaction();
        }
    }

    private ApplicationContext context;
    private DataSource dataSource;
    private Notes notes;

    @BeforeEach
    void start() {
        context = new ApplicationContext(Config.class, NotesImpl.class, ClassNotes.class);
        dataSource = context.getBean(DataSource.class);
        notes = context.getBean(Notes.class);
    }

    @AfterEach
    void stop() {
        context.close();
    }

    private List<String> rows() {
        return H2Support.committedTags(dataSource);
    }

    @Test
    void anInterfaceBeanGetsAJdkProxyAndAClassBeanAGeneratedSubclass() {
        assertTrue(Proxy.isProxyClass(notes.getClass()));
        assertInstanceOf(GeneratedProxy.class, context.getBean(ClassNotes.class));
    }

    @Test
    void successCommitsAndRuntimeExceptionRollsBack() {
        notes.save("kept");
        assertThrows(IllegalStateException.class, () -> notes.saveThenFail("lost"));
        assertEquals(List.of("kept"), rows());
    }

    @Test
    void theSameRulesApplyToAGeneratedSubclassProxy() {
        ClassNotes classNotes = context.getBean(ClassNotes.class);
        classNotes.save("kept");
        assertThrows(IllegalStateException.class, () -> classNotes.saveThenFail("lost"));
        assertEquals(List.of("kept"), rows());
    }

    @Test
    void checkedExceptionsCommitByDefault() {
        assertThrows(BusinessException.class, () -> notes.saveThenFailChecked("kept"));
        assertEquals(List.of("kept"), rows());
    }

    @Test
    void rollbackForMakesACheckedExceptionRollBack() {
        assertThrows(BusinessException.class, () -> notes.saveThenFailCheckedWithRollbackFor("lost"));
        assertEquals(List.of(), rows());
    }

    @Test
    void theRuleClosestToTheThrownExceptionWins() {
        assertThrows(SpecificBusinessException.class, () -> notes.saveThenFailSpecific("kept"));
        assertEquals(List.of("kept"), rows());
    }

    @Test
    void noRollbackForOverridesTheUncheckedDefault() {
        assertThrows(IllegalStateException.class, () -> notes.saveThenFailUncheckedButExcluded("kept"));
        assertEquals(List.of("kept"), rows());
    }

    @Test
    void rollbackRulesAreDecidedByTheDefinitionAlone() {
        TransactionDefinition definition = new TransactionDefinition("t", Propagation.REQUIRED, Isolation.DEFAULT,
                false, List.of(Exception.class), List.of(BusinessException.class));
        assertTrue(definition.rollbackOn(new SQLException()), "Exception rule");
        assertFalse(definition.rollbackOn(new BusinessException("x")), "exact class of the noRollback rule");
        assertFalse(definition.rollbackOn(new SpecificBusinessException("x")), "subclass is nearer to BusinessException");
        assertTrue(definition.rollbackOn(new OutOfMemoryError()), "errors roll back");
        assertFalse(TransactionDefinition.of(Propagation.REQUIRED).rollbackOn(new SQLException()),
                "default: checked commits");
    }

    // ---- the self-invocation pitfall ---------------------------------------------------------------------------

    @Test
    void selfInvocationThroughAnInterfaceProxyBypassesTheTransaction() {
        assertTrue(notes.runsInTransaction(), "called through the proxy: REQUIRES_NEW starts a transaction");
        assertFalse(notes.callInnerViaThis(),
                "callInnerViaThis() calls this.runsInTransaction() on the target, not on the proxy: no advice, no transaction");
    }

    @Test
    void aGeneratedSubclassProxyDoesInterceptSelfInvocationBecauseThisIsTheProxy() {
        ClassNotes classNotes = context.getBean(ClassNotes.class);
        assertTrue(classNotes.callInnerViaThis(),
                "unlike the interface proxy there is no separate target: this.runsInTransaction() is virtual "
                        + "dispatch to the generated override, so the advice runs");
    }

    // ---- connection and settings handling -------------------------------------------------------------------------

    @Test
    void readOnlyAndIsolationAreAppliedForTheCallAndRestoredBeforeTheConnectionIsReturned() throws Throwable {
        // H2 ignores setReadOnly, so the calls are observed on a recording connection instead.
        List<String> calls = new ArrayList<>();
        TransactionManager manager = new TransactionManager(H2Support.recording(dataSource, calls));
        manager.execute(new TransactionDefinition("probe", Propagation.REQUIRED, Isolation.SERIALIZABLE, true,
                List.of(), List.of()), () -> null);
        assertEquals(List.of("setTransactionIsolation(8)", "setReadOnly(true)", "setAutoCommit(false)", "commit",
                "setAutoCommit(true)", "setReadOnly(false)", "setTransactionIsolation(2)", "close"), calls);
    }

    @Test
    void isolationIsReallyInForceOnTheConnectionInsideTheCall() {
        assertEquals("readOnly=false isolation=" + Connection.TRANSACTION_SERIALIZABLE + " autoCommit=false",
                notes.connectionSettings());
        try (Connection fresh = dataSource.getConnection()) {
            assertTrue(fresh.getAutoCommit());
            assertEquals(Connection.TRANSACTION_READ_COMMITTED, fresh.getTransactionIsolation());
        } catch (SQLException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void noConnectionStaysBoundToTheThreadAfterAFailure() {
        assertThrows(IllegalStateException.class, () -> notes.saveThenFail("x"));
        assertFalse(TransactionContext.isActive(dataSource));
    }

    @Test
    void concurrentCallsEachGetTheirOwnTransaction() throws Exception {
        int workers = 8;
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            int n = i;
            futures.add(pool.submit(() -> {
                start.await();
                if (n % 2 == 0) {
                    notes.save("ok-" + n);
                } else {
                    assertThrows(IllegalStateException.class, () -> notes.saveThenFail("lost-" + n));
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get();
        }
        pool.close();
        List<String> committed = rows();
        assertEquals(workers / 2, committed.size());
        assertTrue(committed.stream().allMatch(tag -> tag.startsWith("ok-")), committed.toString());
    }

    @Test
    void aTransactionDoesNotFollowWorkIntoAnotherThread() throws Throwable {
        TransactionManager manager = context.getBean(TransactionManager.class);
        boolean[] seenInOtherThread = new boolean[1];
        manager.execute(TransactionDefinition.of(Propagation.REQUIRED), () -> {
            Thread other = Thread.ofVirtual().start(() -> seenInOtherThread[0] = TransactionContext.isActive(dataSource));
            other.join();
            return null;
        });
        assertFalse(seenInOtherThread[0]);
    }
}

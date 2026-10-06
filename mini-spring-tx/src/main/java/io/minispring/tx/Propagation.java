package io.minispring.tx;

/** What a transactional method does when a transaction is, or is not, already running on the thread. */
public enum Propagation {
    /** Join the running transaction; start one if there is none. The default. */
    REQUIRED,
    /** Always start a new transaction; a running one is suspended meanwhile and resumed afterwards. */
    REQUIRES_NEW,
    /** Join a running transaction; otherwise run without one. */
    SUPPORTS,
    /** Join a running transaction; fail with {@link IllegalTransactionStateException} if there is none. */
    MANDATORY,
    /** Run without a transaction; fail with {@link IllegalTransactionStateException} if one is running. */
    NEVER
}

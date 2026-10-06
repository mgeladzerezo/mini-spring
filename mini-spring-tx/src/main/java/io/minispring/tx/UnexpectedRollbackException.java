package io.minispring.tx;

/**
 * The outermost method asked for a commit, but a joined inner method had already marked the shared
 * transaction rollback-only, so it was rolled back instead. The inner caller swallowing the inner
 * exception is the usual cause.
 */
public class UnexpectedRollbackException extends TransactionException {

    public UnexpectedRollbackException(String message) {
        super(message);
    }
}

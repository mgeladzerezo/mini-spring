package io.minispring.tx;

/** A JDBC failure while beginning, committing or rolling back. */
public class TransactionSystemException extends TransactionException {

    public TransactionSystemException(String message, Throwable cause) {
        super(message, cause);
    }
}

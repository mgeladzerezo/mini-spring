package io.minispring.tx;

/** Base class of failures raised by the transaction machinery itself. */
public class TransactionException extends RuntimeException {

    public TransactionException(String message) {
        super(message);
    }

    public TransactionException(String message, Throwable cause) {
        super(message, cause);
    }
}

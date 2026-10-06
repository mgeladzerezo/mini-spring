package io.minispring.tx;

/** Raised for {@link Propagation#MANDATORY} without a transaction and {@link Propagation#NEVER} inside one. */
public class IllegalTransactionStateException extends TransactionException {

    public IllegalTransactionStateException(String message) {
        super(message);
    }
}

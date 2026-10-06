package io.minispring.demo.domain;

/** Unchecked, so the surrounding transaction rolls back by default. */
public class InsufficientFundsException extends RuntimeException {

    public InsufficientFundsException(long accountId) {
        super("Account " + accountId + " does not have enough funds");
    }
}

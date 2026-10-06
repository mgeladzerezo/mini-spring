package io.minispring.demo.domain;

public class NoSuchAccountException extends RuntimeException {

    public NoSuchAccountException(long accountId) {
        super("No account with id " + accountId);
    }
}

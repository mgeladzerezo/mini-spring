package io.minispring.aop.proxy;

/** A class cannot be proxied the way its advice requires; the message lists every obstacle. */
public class ProxyCreationException extends RuntimeException {

    public ProxyCreationException(String message) {
        super(message);
    }

    public ProxyCreationException(String message, Throwable cause) {
        super(message, cause);
    }
}

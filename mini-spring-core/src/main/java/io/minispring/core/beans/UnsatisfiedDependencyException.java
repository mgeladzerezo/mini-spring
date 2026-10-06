package io.minispring.core.beans;

/** An injection point could not be satisfied; the message names the exact member. */
public class UnsatisfiedDependencyException extends BeansException {

    public UnsatisfiedDependencyException(InjectionPoint point, Throwable cause) {
        super("Unsatisfied dependency at " + point.description() + ": " + cause.getMessage(), cause);
    }
}

package io.minispring.aop.proxy;

/** Receives every call made on a generated proxy's overridden methods. */
@FunctionalInterface
public interface ProxyDispatcher {

    /**
     * @param self        the proxy instance
     * @param methodIndex index of the called method in the proxy's method table
     * @param arguments   boxed arguments
     * @return the (boxed) result
     */
    Object dispatch(Object self, int methodIndex, Object[] arguments) throws Throwable;
}

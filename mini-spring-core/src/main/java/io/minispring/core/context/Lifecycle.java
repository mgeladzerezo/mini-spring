package io.minispring.core.context;

/**
 * A bean with a running state, such as a server. {@link #start()} is called after every
 * singleton exists, in {@code @Order} order; {@link #stop()} when the context closes, in
 * reverse order and before any bean is destroyed.
 */
public interface Lifecycle {

    void start();

    void stop();
}

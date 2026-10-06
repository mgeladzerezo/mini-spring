package io.minispring.core.beans;

import java.util.List;

/**
 * Beans depend on each other in a loop. The message prints the loop, e.g. {@code a -> b -> c -> a}.
 *
 * <p>The container refuses every cycle instead of handing out half-built "early references".
 * A constructor cycle cannot be built at all (each constructor needs the other's finished
 * object). A field cycle could be, but only by publishing an object before post-processors ran,
 * so one side may capture the raw bean while everyone else gets its transactional proxy.
 */
public class CircularDependencyException extends BeansException {

    private final List<String> path;

    public CircularDependencyException(List<String> path) {
        super("Circular dependency: " + String.join(" -> ", path)
                + ". Break the cycle by injecting Provider<T> (or an @Lazy interface) on one side, "
                + "or by extracting the shared logic into a third bean.");
        this.path = List.copyOf(path);
    }

    /** Bean names along the cycle; the first and last element are the same bean. */
    public List<String> path() {
        return path;
    }
}

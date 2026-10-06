package io.minispring.core.annotation;

/** The two lifecycles the container manages. */
public enum BeanScope {
    /** One shared instance per context, created at startup unless {@link Lazy}. */
    SINGLETON,
    /** A new instance for every lookup or injection; the container does not track or destroy it. */
    PROTOTYPE
}

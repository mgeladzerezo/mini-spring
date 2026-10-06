package io.minispring.core.beans;

import io.minispring.core.annotation.BeanScope;
import java.util.List;

/**
 * What the container observed while creating one bean; the raw material of the startup report.
 *
 * @param name         bean name
 * @param definition   the definition that was instantiated
 * @param exposedClass runtime class of the object other beans receive (differs from the bean
 *                     class when a post-processor substituted or wrapped it)
 * @param totalNanos   wall time including dependencies created on demand
 * @param selfNanos    wall time excluding those dependencies
 * @param dependencies names of the beans injected into it, in resolution order
 */
public record BeanCreationRecord(String name, BeanDefinition definition, Class<?> exposedClass, long totalNanos,
                                 long selfNanos, List<String> dependencies) {

    public BeanScope scope() {
        return definition.scope();
    }
}

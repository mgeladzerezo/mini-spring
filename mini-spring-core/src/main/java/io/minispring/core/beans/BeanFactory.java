package io.minispring.core.beans;

import io.minispring.core.type.TypeReference;
import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Map;

/** Read access to the container: look beans up by name, class or full generic type. */
public interface BeanFactory {

    /**
     * @throws NoSuchBeanException if no bean has this name
     */
    Object getBean(String name);

    /**
     * @throws BeanNotOfRequiredTypeException if the bean is not an instance of {@code requiredType}
     */
    <T> T getBean(String name, Class<T> requiredType);

    /**
     * Looks a bean up by class. Among several candidates a {@code @Primary} bean wins.
     *
     * @throws NoSuchBeanException   if none matches
     * @throws NoUniqueBeanException if several match
     */
    <T> T getBean(Class<T> type);

    /**
     * Looks a bean up by generic type:
     * {@code getBean(new TypeReference<Repository<User>>() {})}.
     */
    <T> T getBean(TypeReference<T> type);

    /** A lazy handle for the given class; nothing is resolved until it is used. */
    <T> Provider<T> getProvider(Class<T> type);

    /** A lazy handle for the given generic type. */
    <T> Provider<T> getProvider(TypeReference<T> type);

    /** All beans assignable to {@code type}, keyed by name, in {@code @Order} order. */
    <T> Map<String, T> getBeansOfType(Class<T> type);

    /** All beans whose class carries the annotation, directly or as a meta-annotation. */
    Map<String, Object> getBeansWithAnnotation(Class<? extends Annotation> annotationType);

    boolean containsBean(String name);

    /** Names of all bean definitions in registration order. */
    List<String> getBeanNames();

    /**
     * @throws NoSuchBeanException if no bean has this name
     */
    BeanDefinition getBeanDefinition(String name);
}

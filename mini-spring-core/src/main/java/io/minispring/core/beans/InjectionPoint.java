package io.minispring.core.beans;

import io.minispring.core.annotation.Autowired;
import io.minispring.core.annotation.MergedAnnotations;
import io.minispring.core.type.ResolvedType;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Parameter;

/**
 * One place a dependency goes: a field or a constructor/method parameter, with its generic
 * type already resolved against the concrete bean class.
 *
 * @param type        the fully resolved type to satisfy
 * @param element     the field or parameter, for reading {@code @Qualifier}, {@code @Value}, {@code @Lazy}
 * @param name        field or parameter name, used as a last-resort tie breaker
 * @param required    whether a missing bean is an error
 * @param description where this is, for error messages
 */
public record InjectionPoint(ResolvedType type, AnnotatedElement element, String name, boolean required,
                             String description) {

    /** Stands in for the member of a programmatic lookup, which has no annotations to read. */
    private static final AnnotatedElement NO_ANNOTATIONS = new AnnotatedElement() {
        @Override
        public <A extends Annotation> A getAnnotation(Class<A> annotationClass) {
            return null;
        }

        @Override
        public Annotation[] getAnnotations() {
            return new Annotation[0];
        }

        @Override
        public Annotation[] getDeclaredAnnotations() {
            return new Annotation[0];
        }
    };

    /**
     * @param implementationClass the concrete bean class; type variables in the field's type are
     *                            resolved from its point of view
     */
    public static InjectionPoint forField(Field field, Class<?> implementationClass) {
        boolean required = MergedAnnotations.find(field, Autowired.class).map(Autowired::required).orElse(true);
        return new InjectionPoint(ResolvedType.forField(field, implementationClass), field, field.getName(), required,
                "field '" + field.getName() + "' of " + field.getDeclaringClass().getName());
    }

    public static InjectionPoint forParameter(Executable executable, int index, Class<?> implementationClass) {
        Parameter parameter = executable.getParameters()[index];
        boolean required = MergedAnnotations.find(parameter, Autowired.class).map(Autowired::required)
                .or(() -> MergedAnnotations.find(executable, Autowired.class).map(Autowired::required))
                .orElse(true);
        String owner = executable instanceof Constructor<?>
                ? "constructor of " + executable.getDeclaringClass().getName()
                : "method " + executable.getDeclaringClass().getName() + "." + executable.getName() + "()";
        return new InjectionPoint(ResolvedType.forParameter(parameter, implementationClass), parameter,
                parameter.getName(), required, "parameter '" + parameter.getName() + "' (#" + index + ") of " + owner);
    }

    /** A programmatic lookup such as {@code getBean(type)}: no annotations, no name. */
    public static InjectionPoint forLookup(ResolvedType type) {
        return new InjectionPoint(type, NO_ANNOTATIONS, "", true, "lookup of " + type);
    }

    /** The same place asking for a different type, e.g. the {@code T} inside {@code Optional<T>}. */
    public InjectionPoint withType(ResolvedType newType, boolean newRequired) {
        return new InjectionPoint(newType, element, name, newRequired, description);
    }
}

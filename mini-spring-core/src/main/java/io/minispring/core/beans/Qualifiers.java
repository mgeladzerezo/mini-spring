package io.minispring.core.beans;

import io.minispring.core.annotation.MergedAnnotations;
import io.minispring.core.annotation.Qualifier;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Turns qualifier annotations into comparable keys.
 *
 * <p>{@code @Qualifier("fast")} yields the key {@code fast}. A custom annotation that is
 * meta-annotated with {@code @Qualifier}, say {@code @Region("eu")}, yields a key made of its
 * type and attribute values, so the bean and the injection point match exactly when they carry
 * equal annotations.
 */
public final class Qualifiers {

    private Qualifiers() {
    }

    /** Qualifier keys declared directly on a class, factory method, field or parameter. */
    public static Set<String> of(AnnotatedElement element) {
        Set<String> keys = new LinkedHashSet<>();
        for (Annotation carrier : MergedAnnotations.declaredCarriers(element, Qualifier.class)) {
            if (carrier instanceof Qualifier qualifier) {
                if (!qualifier.value().isEmpty()) {
                    keys.add(qualifier.value());
                }
            } else {
                keys.add(customKey(carrier));
            }
        }
        return keys;
    }

    private static String customKey(Annotation carrier) {
        StringJoiner joiner = new StringJoiner(",", "@" + carrier.annotationType().getName() + "(", ")");
        for (Map.Entry<String, Object> attribute : MergedAnnotations.readAttributes(carrier).entrySet()) {
            Object value = attribute.getValue();
            String text = value.getClass().isArray() ? Arrays.deepToString(new Object[]{value}) : String.valueOf(value);
            joiner.add(attribute.getKey() + "=" + text);
        }
        return joiner.toString();
    }

    /** Renders a key for an error message. */
    static String display(String key) {
        if (!key.startsWith("@")) {
            return "'" + key + "'";
        }
        String simple = key.substring(key.lastIndexOf('.', key.indexOf('(')) + 1);
        return "@" + simple.replace("()", "");
    }
}

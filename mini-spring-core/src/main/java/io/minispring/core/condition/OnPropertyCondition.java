package io.minispring.core.condition;

import io.minispring.core.annotation.ConditionalOnProperty;
import io.minispring.core.annotation.MergedAnnotations;
import java.lang.reflect.AnnotatedElement;
import java.util.Optional;

/** Backs {@link ConditionalOnProperty}. */
public final class OnPropertyCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedElement element) {
        ConditionalOnProperty condition = MergedAnnotations.find(element, ConditionalOnProperty.class).orElseThrow();
        Optional<String> value = context.environment().getProperty(condition.name());
        if (value.isEmpty()) {
            return condition.matchIfMissing();
        }
        return condition.havingValue().isEmpty()
                ? !"false".equalsIgnoreCase(value.get().strip())
                : condition.havingValue().equalsIgnoreCase(value.get().strip());
    }

    @Override
    public String describeMismatch(ConditionContext context, AnnotatedElement element) {
        ConditionalOnProperty condition = MergedAnnotations.find(element, ConditionalOnProperty.class).orElseThrow();
        String actual = context.environment().getProperty(condition.name())
                .map(value -> "is '" + value + "'").orElse("is not set");
        String expected = condition.havingValue().isEmpty() ? "" : " to be '" + condition.havingValue() + "'";
        return "@ConditionalOnProperty expects '" + condition.name() + "'" + expected + " but it " + actual;
    }
}

package io.minispring.core.condition;

import io.minispring.core.annotation.MergedAnnotations;
import io.minispring.core.annotation.Profile;
import java.lang.reflect.AnnotatedElement;
import java.util.Arrays;

/** Backs {@link Profile}. */
public final class ProfileCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedElement element) {
        Profile profile = MergedAnnotations.find(element, Profile.class).orElseThrow();
        return context.environment().acceptsProfiles(profile.value());
    }

    @Override
    public String describeMismatch(ConditionContext context, AnnotatedElement element) {
        Profile profile = MergedAnnotations.find(element, Profile.class).orElseThrow();
        return "@Profile(" + Arrays.toString(profile.value()) + ") does not match active profiles "
                + context.environment().activeProfiles();
    }
}

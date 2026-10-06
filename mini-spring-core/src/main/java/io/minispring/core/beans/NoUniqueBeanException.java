package io.minispring.core.beans;

import java.util.List;

/** Several beans match and nothing (qualifier, {@code @Primary}, name) singles one out. */
public class NoUniqueBeanException extends BeansException {

    private final List<String> candidates;

    public NoUniqueBeanException(String message, List<String> candidates) {
        super(message);
        this.candidates = List.copyOf(candidates);
    }

    /** Names of the beans that matched. */
    public List<String> candidates() {
        return candidates;
    }
}

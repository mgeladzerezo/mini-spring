package io.minispring.core.env;

/** A <code>${key}</code> placeholder has no value and no default. */
public class UnresolvedPlaceholderException extends RuntimeException {

    private final String key;

    public UnresolvedPlaceholderException(String key, String text, String reason) {
        super("Could not resolve placeholder '" + key + "' in \"" + text + "\": " + reason);
        this.key = key;
    }

    public String key() {
        return key;
    }
}

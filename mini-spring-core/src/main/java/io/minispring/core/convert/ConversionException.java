package io.minispring.core.convert;

import io.minispring.core.type.ResolvedType;

/** A value could not be converted to the requested type. */
public class ConversionException extends RuntimeException {

    public ConversionException(Object source, ResolvedType target, String reason, Throwable cause) {
        super("Cannot convert " + describe(source) + " to " + target + (reason == null ? "" : ": " + reason), cause);
    }

    private static String describe(Object source) {
        if (source == null) {
            return "null";
        }
        return source instanceof CharSequence ? "\"" + source + "\"" : source.getClass().getSimpleName() + " " + source;
    }
}

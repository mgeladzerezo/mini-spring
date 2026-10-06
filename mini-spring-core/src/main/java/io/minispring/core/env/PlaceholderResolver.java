package io.minispring.core.env;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Expands <code>${key}</code> and <code>${key:default}</code> placeholders.
 *
 * <p>A small recursive-descent expander rather than a regex, because placeholders nest:
 * a default may itself be a placeholder (<code>${a:${b:c}}</code>), a key may be built from
 * one (<code>${db.${env}.url}</code>), and a looked-up value may contain more placeholders.
 * Keys currently being expanded are tracked so that two properties referring to each other
 * fail with a clear message instead of a stack overflow.
 */
final class PlaceholderResolver {

    private final Function<String, Optional<String>> lookup;

    PlaceholderResolver(Function<String, Optional<String>> lookup) {
        this.lookup = lookup;
    }

    String resolve(String text) {
        return resolve(text, text, new LinkedHashSet<>());
    }

    private String resolve(String text, String original, Set<String> inProgress) {
        int start = text.indexOf("${");
        if (start < 0) {
            return text;
        }
        StringBuilder out = new StringBuilder();
        int position = 0;
        while (start >= 0) {
            int end = matchingBrace(text, start + 2);
            if (end < 0) {
                break; // an unterminated placeholder is kept as literal text
            }
            out.append(text, position, start);
            out.append(expand(text.substring(start + 2, end), original, inProgress));
            position = end + 1;
            start = text.indexOf("${", position);
        }
        return out.append(text, position, text.length()).toString();
    }

    private String expand(String body, String original, Set<String> inProgress) {
        int separator = topLevelColon(body);
        String key = resolve(separator < 0 ? body : body.substring(0, separator), original, inProgress).strip();
        if (!inProgress.add(key)) {
            throw new UnresolvedPlaceholderException(key, original,
                    "circular reference " + String.join(" -> ", inProgress) + " -> " + key);
        }
        try {
            Optional<String> value = lookup.apply(key);
            if (value.isPresent()) {
                return resolve(value.get(), original, inProgress);
            }
            if (separator >= 0) {
                return resolve(body.substring(separator + 1), original, inProgress);
            }
            throw new UnresolvedPlaceholderException(key, original, "no such property and no default given");
        } finally {
            inProgress.remove(key);
        }
    }

    private static boolean opensPlaceholder(String text, int index) {
        return text.charAt(index) == '$' && index + 1 < text.length() && text.charAt(index + 1) == '{';
    }

    private static int matchingBrace(String text, int from) {
        int depth = 0;
        for (int i = from; i < text.length(); i++) {
            if (opensPlaceholder(text, i)) {
                depth++;
                i++;
            } else if (text.charAt(i) == '}') {
                if (depth == 0) {
                    return i;
                }
                depth--;
            }
        }
        return -1;
    }

    /** Finds the key/default separator, ignoring colons inside nested placeholders. */
    private static int topLevelColon(String body) {
        int depth = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (opensPlaceholder(body, i)) {
                depth++;
                i++;
            } else if (c == '}' && depth > 0) {
                depth--;
            } else if (c == ':' && depth == 0) {
                return i;
            }
        }
        return -1;
    }
}

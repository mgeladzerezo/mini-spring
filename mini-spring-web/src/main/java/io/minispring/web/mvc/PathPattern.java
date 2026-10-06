package io.minispring.web.mvc;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A URL path template: literal segments, {@code {name}} (one whole segment, captured), {@code *}
 * (one segment, not captured) and a final {@code **} (zero or more segments).
 *
 * <p>Matching works on the still-encoded path split at {@code /}, and each captured segment is
 * decoded afterwards, so {@code /files/a%2Fb} captures {@code a/b} for {@code /files/{name}}
 * instead of being mistaken for two segments.
 *
 * <p>Ordering ({@link #compareTo}) implements the "most specific wins" rule: a pattern ranks by
 * {@code variables + wildcards + 2 * doubleWildcard} (lower is more specific), and among equals
 * the one with more literal characters comes first. {@code /users/me} therefore beats
 * {@code /users/{id}}, which beats {@code /users/*}-style catch-alls, which beat {@code /users/**}.
 */
public final class PathPattern implements Comparable<PathPattern> {

    private enum Kind { LITERAL, VARIABLE, WILDCARD, MULTI }

    private record Segment(Kind kind, String text) {
    }

    private final String pattern;
    private final List<Segment> segments;
    private final int score;
    private final int literalLength;

    private PathPattern(String pattern, List<Segment> segments) {
        this.pattern = pattern;
        this.segments = segments;
        int specificity = 0;
        int literals = 0;
        for (Segment segment : segments) {
            switch (segment.kind()) {
                case VARIABLE, WILDCARD -> specificity++;
                case MULTI -> specificity += 2;
                case LITERAL -> literals += segment.text().length();
            }
        }
        this.score = specificity;
        this.literalLength = literals;
    }

    /**
     * @throws IllegalArgumentException for a malformed template (variable inside a segment, {@code **}
     *                                  before the end, duplicate variable names)
     */
    public static PathPattern parse(String template) {
        String normalized = template.startsWith("/") ? template : "/" + template;
        List<Segment> segments = new ArrayList<>();
        Set<String> names = new HashSet<>();
        if (!normalized.equals("/")) {
            String[] parts = normalized.substring(1).split("/", -1);
            for (int i = 0; i < parts.length; i++) {
                String part = parts[i];
                if (part.equals("**")) {
                    if (i != parts.length - 1) {
                        throw new IllegalArgumentException("'**' is only allowed as the last segment: " + template);
                    }
                    segments.add(new Segment(Kind.MULTI, part));
                } else if (part.equals("*")) {
                    segments.add(new Segment(Kind.WILDCARD, part));
                } else if (part.startsWith("{") && part.endsWith("}") && part.length() > 2) {
                    String name = part.substring(1, part.length() - 1);
                    if (name.contains("{") || name.contains("}")) {
                        throw new IllegalArgumentException("Malformed variable '" + part + "' in " + template);
                    }
                    if (!names.add(name)) {
                        throw new IllegalArgumentException("Variable '" + name + "' appears twice in " + template);
                    }
                    segments.add(new Segment(Kind.VARIABLE, name));
                } else if (part.contains("{") || part.contains("}") || part.contains("*")) {
                    throw new IllegalArgumentException("A variable or wildcard must be a whole path segment, but found '"
                            + part + "' in " + template);
                } else {
                    segments.add(new Segment(Kind.LITERAL, part));
                }
            }
        }
        return new PathPattern(normalized, List.copyOf(segments));
    }

    /** The captured variables if the path matches, in pattern order; empty if it does not. */
    public Optional<Map<String, String>> match(String path) {
        String[] parts = path.equals("/") ? new String[0] : path.substring(path.startsWith("/") ? 1 : 0).split("/", -1);
        Map<String, String> variables = new LinkedHashMap<>();
        for (int i = 0; i < segments.size(); i++) {
            Segment segment = segments.get(i);
            if (segment.kind() == Kind.MULTI) {
                return Optional.of(variables);
            }
            if (i >= parts.length) {
                return Optional.empty();
            }
            String part = parts[i];
            switch (segment.kind()) {
                case LITERAL -> {
                    if (!decode(part).equals(segment.text())) {
                        return Optional.empty();
                    }
                }
                case VARIABLE -> {
                    if (part.isEmpty()) {
                        return Optional.empty();
                    }
                    variables.put(segment.text(), decode(part));
                }
                case WILDCARD -> {
                    if (part.isEmpty()) {
                        return Optional.empty();
                    }
                }
                case MULTI -> throw new AssertionError();
            }
        }
        return parts.length == segments.size() ? Optional.of(variables) : Optional.empty();
    }

    /**
     * The pattern with variable names erased: {@code /users/{id}} and {@code /users/{name}} have the
     * same shape and would match exactly the same requests, which makes them ambiguous.
     */
    public String shape() {
        StringBuilder shape = new StringBuilder();
        for (Segment segment : segments) {
            shape.append('/').append(segment.kind() == Kind.VARIABLE ? "{}" : segment.text());
        }
        return shape.isEmpty() ? "/" : shape.toString();
    }

    /** The names of the variables this pattern captures. */
    public List<String> variableNames() {
        return segments.stream().filter(s -> s.kind() == Kind.VARIABLE).map(Segment::text).toList();
    }

    /** Concatenates a class-level prefix and a method-level pattern: {@code /orders} + {@code {id}}. */
    public static String combine(String prefix, String suffix) {
        String left = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
        if (suffix.isEmpty() || suffix.equals("/")) {
            return left.isEmpty() ? "/" : left;
        }
        String right = suffix.startsWith("/") ? suffix : "/" + suffix;
        String combined = left + right;
        return combined.startsWith("/") ? combined : "/" + combined;
    }

    private static String decode(String segment) {
        if (segment.indexOf('%') < 0 && segment.indexOf('+') < 0) {
            return segment;
        }
        try {
            // '+' means a space in query strings but is a literal plus in paths
            return URLDecoder.decode(segment.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            return segment;
        }
    }

    @Override
    public int compareTo(PathPattern other) {
        if (score != other.score) {
            return Integer.compare(score, other.score);
        }
        return Integer.compare(other.literalLength, literalLength);
    }

    @Override
    public String toString() {
        return pattern;
    }
}

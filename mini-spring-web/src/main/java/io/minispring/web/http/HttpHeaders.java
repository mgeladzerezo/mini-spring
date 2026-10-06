package io.minispring.web.http;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Header fields with case-insensitive names and possibly several values per name. */
public final class HttpHeaders {

    private final Map<String, List<String>> fields = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    /** Appends a value, keeping earlier ones. */
    public HttpHeaders add(String name, String value) {
        fields.computeIfAbsent(name, key -> new ArrayList<>()).add(value);
        return this;
    }

    /** Replaces all values of the header. */
    public HttpHeaders set(String name, String value) {
        List<String> values = new ArrayList<>();
        values.add(value);
        fields.put(name, values);
        return this;
    }

    /** The first value, or {@code null}. */
    public String first(String name) {
        List<String> values = fields.get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    public List<String> all(String name) {
        List<String> values = fields.get(name);
        return values == null ? List.of() : Collections.unmodifiableList(values);
    }

    public boolean contains(String name) {
        return fields.containsKey(name);
    }

    public Map<String, List<String>> asMap() {
        return Collections.unmodifiableMap(fields);
    }

    /** The media type of {@code Content-Type} without parameters, lower-cased; {@code null} if absent. */
    public String mediaType() {
        String value = first("Content-Type");
        if (value == null) {
            return null;
        }
        int semicolon = value.indexOf(';');
        return (semicolon < 0 ? value : value.substring(0, semicolon)).strip().toLowerCase(Locale.ROOT);
    }
}

package io.minispring.web.json;

import io.minispring.core.type.ResolvedType;
import io.minispring.core.type.TypeReference;
import java.nio.charset.StandardCharsets;

/**
 * The public face of the JSON module: parse text to a tree, bind a tree to a (generic) type,
 * serialise an object. Stateless apart from one option, so one instance serves the whole
 * application.
 *
 * <pre>{@code
 * List<OrderDto> orders = mapper.readValue(text, new TypeReference<List<OrderDto>>() {});
 * }</pre>
 */
public final class JsonMapper {

    private final JsonBinder binder;

    /** A mapper that ignores JSON properties the target type does not have. */
    public JsonMapper() {
        this(false);
    }

    /**
     * @param failOnUnknownProperties reject objects with properties the target record or bean lacks
     */
    public JsonMapper(boolean failOnUnknownProperties) {
        this.binder = new JsonBinder(failOnUnknownProperties);
    }

    /** Parses text into maps, lists, strings, numbers ({@code Long} or {@code BigDecimal}), booleans and null. */
    public Object readTree(String json) {
        return JsonParser.parse(json);
    }

    public <T> T readValue(String json, Class<T> type) {
        @SuppressWarnings("unchecked") // the binder returns an instance of the boxed raw class
        T value = (T) readValue(json, ResolvedType.forClass(type));
        return value;
    }

    public <T> T readValue(String json, TypeReference<T> type) {
        @SuppressWarnings("unchecked") // the reference captured T
        T value = (T) readValue(json, type.resolved());
        return value;
    }

    /** Parses and binds to a type that may be generic, e.g. one taken from a method parameter. */
    public Object readValue(String json, ResolvedType type) {
        return binder.bind(JsonParser.parse(json), type);
    }

    public Object readValue(byte[] json, ResolvedType type) {
        return readValue(new String(json, StandardCharsets.UTF_8), type);
    }

    public String writeValueAsString(Object value) {
        return JsonWriter.write(value);
    }

    public byte[] writeValueAsBytes(Object value) {
        return JsonWriter.write(value).getBytes(StandardCharsets.UTF_8);
    }
}

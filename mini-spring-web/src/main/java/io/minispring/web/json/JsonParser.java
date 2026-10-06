package io.minispring.web.json;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A strict RFC 8259 parser producing a plain Java tree: {@code Map<String,Object>} (insertion
 * ordered), {@code List<Object>}, {@code String}, {@code Long} for integers that fit,
 * {@code BigDecimal} for every other number (so no precision is lost before the binder decides
 * what the target type is), {@code Boolean} and {@code null}.
 *
 * <p>Strictness is deliberate: no comments, no trailing commas, no leading zeros, no unescaped
 * control characters, no content after the value. Nesting is limited so that a hostile
 * request cannot exhaust the stack.
 */
final class JsonParser {

    private static final int MAX_DEPTH = 256;

    private final String text;
    private int position;

    private JsonParser(String text) {
        this.text = text;
    }

    static Object parse(String text) {
        JsonParser parser = new JsonParser(text);
        parser.skipWhitespace();
        Object value = parser.readValue(0);
        parser.skipWhitespace();
        if (parser.position < text.length()) {
            throw parser.error("unexpected content after the JSON value");
        }
        return value;
    }

    private Object readValue(int depth) {
        if (depth > MAX_DEPTH) {
            throw error("nesting deeper than " + MAX_DEPTH + " levels");
        }
        if (position >= text.length()) {
            throw error("unexpected end of input, expected a value");
        }
        char c = text.charAt(position);
        return switch (c) {
            case '{' -> readObject(depth);
            case '[' -> readArray(depth);
            case '"' -> readString();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> {
                if (c == '-' || (c >= '0' && c <= '9')) {
                    yield readNumber();
                }
                throw error("unexpected character '" + printable(c) + "', expected a value");
            }
        };
    }

    private Map<String, Object> readObject(int depth) {
        position++; // {
        Map<String, Object> members = new LinkedHashMap<>();
        skipWhitespace();
        if (peek() == '}') {
            position++;
            return members;
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') {
                throw error("expected a property name in double quotes");
            }
            String name = readString();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            members.put(name, readValue(depth + 1)); // a repeated name keeps the last value, like most parsers
            skipWhitespace();
            char next = peek();
            position++;
            if (next == '}') {
                return members;
            }
            if (next != ',') {
                position--;
                throw error("expected ',' or '}' after a property value");
            }
        }
    }

    private List<Object> readArray(int depth) {
        position++; // [
        List<Object> elements = new ArrayList<>();
        skipWhitespace();
        if (peek() == ']') {
            position++;
            return elements;
        }
        while (true) {
            skipWhitespace();
            elements.add(readValue(depth + 1));
            skipWhitespace();
            char next = peek();
            position++;
            if (next == ']') {
                return elements;
            }
            if (next != ',') {
                position--;
                throw error("expected ',' or ']' after an array element");
            }
        }
    }

    private String readString() {
        position++; // opening quote
        StringBuilder out = null;
        int runStart = position;
        while (true) {
            if (position >= text.length()) {
                throw error("unterminated string");
            }
            char c = text.charAt(position);
            if (c == '"') {
                String tail = text.substring(runStart, position);
                position++;
                return out == null ? tail : out.append(tail).toString();
            }
            if (c < 0x20) {
                throw error("unescaped control character in string");
            }
            if (c == '\\') {
                if (out == null) {
                    out = new StringBuilder();
                }
                out.append(text, runStart, position);
                position++;
                out.append(readEscape());
                runStart = position;
            } else {
                position++;
            }
        }
    }

    private char readEscape() {
        if (position >= text.length()) {
            throw error("unterminated escape sequence");
        }
        char c = text.charAt(position++);
        return switch (c) {
            case '"', '\\', '/' -> c;
            case 'b' -> '\b';
            case 'f' -> '\f';
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 't' -> '\t';
            case 'u' -> {
                if (position + 4 > text.length()) {
                    throw error("incomplete \\u escape");
                }
                int code = 0;
                for (int i = 0; i < 4; i++) {
                    int digit = Character.digit(text.charAt(position + i), 16);
                    if (digit < 0) {
                        position += i;
                        throw error("invalid hexadecimal digit in \\u escape");
                    }
                    code = code * 16 + digit;
                }
                position += 4;
                yield (char) code; // surrogate pairs arrive as two escapes and combine naturally
            }
            default -> {
                position--;
                throw error("invalid escape '\\" + printable(c) + "'");
            }
        };
    }

    private Object readNumber() {
        int start = position;
        if (peek() == '-') {
            position++;
        }
        if (peek() == '0') {
            position++;
            if (position < text.length() && Character.isDigit(text.charAt(position))) {
                throw error("leading zeros are not allowed");
            }
        } else {
            requireDigits("digit");
        }
        boolean integral = true;
        if (position < text.length() && text.charAt(position) == '.') {
            integral = false;
            position++;
            requireDigits("digit after the decimal point");
        }
        if (position < text.length() && (text.charAt(position) == 'e' || text.charAt(position) == 'E')) {
            integral = false;
            position++;
            if (position < text.length() && (text.charAt(position) == '+' || text.charAt(position) == '-')) {
                position++;
            }
            requireDigits("digit in the exponent");
        }
        String number = text.substring(start, position);
        if (integral && number.length() <= 18) {
            return Long.parseLong(number);
        }
        BigDecimal decimal = new BigDecimal(number);
        if (integral) {
            try {
                return decimal.longValueExact();
            } catch (ArithmeticException tooLarge) {
                return decimal;
            }
        }
        return decimal;
    }

    private void requireDigits(String what) {
        int begin = position;
        while (position < text.length() && text.charAt(position) >= '0' && text.charAt(position) <= '9') {
            position++;
        }
        if (position == begin) {
            throw error("expected a " + what);
        }
    }

    private Object literal(String word, Object value) {
        if (!text.startsWith(word, position)) {
            throw error("unexpected token, expected '" + word + "'");
        }
        position += word.length();
        return value;
    }

    private void skipWhitespace() {
        while (position < text.length()) {
            char c = text.charAt(position);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                position++;
            } else {
                return;
            }
        }
    }

    private char peek() {
        if (position >= text.length()) {
            throw error("unexpected end of input");
        }
        return text.charAt(position);
    }

    private void expect(char expected) {
        if (peek() != expected) {
            throw error("expected '" + expected + "'");
        }
        position++;
    }

    private JsonException error(String message) {
        int line = 1;
        int column = 1;
        for (int i = 0; i < Math.min(position, text.length()); i++) {
            if (text.charAt(i) == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
        }
        return new JsonException("Invalid JSON at line " + line + ", column " + column + ": " + message);
    }

    private static String printable(char c) {
        return c < 0x20 ? String.format("\\u%04x", (int) c) : String.valueOf(c);
    }
}

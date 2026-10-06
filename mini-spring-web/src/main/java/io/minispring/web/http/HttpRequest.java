package io.minispring.web.http;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

/**
 * A request as the dispatcher sees it, independent of any server, so the whole MVC layer can be
 * tested by calling it directly.
 *
 * @param method          the request method
 * @param path            the path exactly as sent (still percent-encoded): path variables are decoded
 *                        after the path is split into segments, so an encoded slash stays inside its segment
 * @param queryParameters decoded query parameters, in order, each name with all its values
 * @param headers         request headers
 * @param body            the request body, empty if none
 */
public record HttpRequest(HttpMethod method, String path, Map<String, List<String>> queryParameters,
                          HttpHeaders headers, byte[] body) {

    /** Builds a request from a request target such as {@code /orders?status=OPEN}. */
    public static HttpRequest of(HttpMethod method, String target) {
        int question = target.indexOf('?');
        String path = question < 0 ? target : target.substring(0, question);
        String query = question < 0 ? "" : target.substring(question + 1);
        return new HttpRequest(method, path, parseQuery(query), new HttpHeaders(), new byte[0]);
    }

    /** A copy with the header added. */
    public HttpRequest withHeader(String name, String value) {
        HttpHeaders copy = new HttpHeaders();
        headers.asMap().forEach((key, values) -> values.forEach(v -> copy.add(key, v)));
        copy.add(name, value);
        return new HttpRequest(method, path, queryParameters, copy, body);
    }

    /** A copy with a UTF-8 body. */
    public HttpRequest withBody(String text) {
        return new HttpRequest(method, path, queryParameters, headers, text.getBytes(StandardCharsets.UTF_8));
    }

    /** A copy with a JSON body and the matching content type. */
    public HttpRequest withJson(String json) {
        return withHeader("Content-Type", "application/json").withBody(json);
    }

    public String bodyAsString() {
        return new String(body, StandardCharsets.UTF_8);
    }

    /** The first value of a query parameter, or {@code null}. */
    public String queryParameter(String name) {
        List<String> values = queryParameters.get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    static Map<String, List<String>> parseQuery(String rawQuery) {
        Map<String, List<String>> parameters = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return parameters;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String name = decode(equals < 0 ? pair : pair.substring(0, equals));
            String value = equals < 0 ? "" : decode(pair.substring(equals + 1));
            parameters.computeIfAbsent(name, key -> new ArrayList<>()).add(value);
        }
        return parameters;
    }

    private static String decode(String text) {
        try {
            return URLDecoder.decode(text, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            return text; // a stray '%' is kept literally rather than failing the whole request
        }
    }
}

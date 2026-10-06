package io.minispring.web.mvc;

import io.minispring.web.http.HttpMethod;
import io.minispring.web.http.HttpRequest;
import io.minispring.web.http.HttpResponse;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/**
 * Serves files from the {@code static/} directory of the class path, as a fallback for requests no
 * controller claims. {@code /} serves {@code index.html}.
 *
 * <p>The request path is decoded <em>before</em> it is checked for {@code ..}, backslashes and NUL
 * characters, so {@code %2e%2e} cannot sneak past, and only names with a file extension are served
 * so that a directory on a file-system class path is never listed.
 */
final class StaticResources {

    private static final String ROOT = "static";
    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"),
            Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("mjs", "text/javascript; charset=utf-8"),
            Map.entry("json", "application/json"),
            Map.entry("txt", "text/plain; charset=utf-8"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("woff2", "font/woff2"));

    private final ClassLoader classLoader;

    StaticResources(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    Optional<HttpResponse> serve(HttpRequest request) {
        if (request.method() != HttpMethod.GET && request.method() != HttpMethod.HEAD) {
            return Optional.empty();
        }
        String decoded;
        try {
            decoded = URLDecoder.decode(request.path().replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
        if (decoded.endsWith("/")) {
            decoded += "index.html";
        }
        if (decoded.contains("\\") || decoded.indexOf('\0') >= 0 || decoded.contains("//")) {
            return Optional.empty();
        }
        for (String segment : decoded.split("/")) {
            if (segment.equals("..") || segment.equals(".")) {
                return Optional.empty();
            }
        }
        String fileName = decoded.substring(decoded.lastIndexOf('/') + 1);
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0) {
            return Optional.empty();
        }
        String contentType = TYPES.getOrDefault(fileName.substring(dot + 1).toLowerCase(java.util.Locale.ROOT),
                "application/octet-stream");
        try (InputStream stream = classLoader.getResourceAsStream(ROOT + decoded)) {
            if (stream == null) {
                return Optional.empty();
            }
            HttpResponse response = new HttpResponse().body(stream.readAllBytes());
            response.headers().set("Content-Type", contentType).set("Cache-Control", "no-cache");
            return Optional.of(response);
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}

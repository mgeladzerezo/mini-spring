package io.minispring.web.mvc;

import io.minispring.web.http.HttpMethod;
import io.minispring.web.http.HttpRequest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The routing table: which handler serves which method and path, and why a request got none.
 *
 * <p>Lookup narrows candidates in a fixed order, and the first stage that leaves nothing decides
 * the error, which is what makes the three failures distinguishable:
 * <ol>
 *   <li>path pattern matches nothing: <b>404</b>;</li>
 *   <li>patterns match but none for this HTTP method: <b>405</b> with an {@code Allow} header;</li>
 *   <li>method matches but the request's content type is not accepted: <b>415</b>;</li>
 *   <li>several routes left: the most specific pattern wins (see {@link PathPattern}); a route that
 *       names the method beats one that accepts any.</li>
 * </ol>
 * Two routes with the same method, the same pattern shape and the same {@code consumes} would match
 * exactly the same requests; registering the second is a startup error naming both handlers, never a
 * silent first-wins.
 */
public final class RouteTable {

    /** One registered mapping. */
    public record Route(PathPattern pattern, Set<HttpMethod> methods, List<String> consumes, HandlerMethod handler) {

        boolean acceptsContentType(String mediaType) {
            return consumes.isEmpty() || (mediaType != null && consumes.stream().anyMatch(c -> c.equalsIgnoreCase(mediaType)));
        }
    }

    /** The route chosen for a request and the path variables it captured. */
    public record Match(Route route, Map<String, String> pathVariables) {
    }

    private final List<Route> routes = new ArrayList<>();
    private final Map<String, Route> byKey = new HashMap<>();

    /**
     * @throws IllegalStateException if an equivalent route is already registered
     */
    public void register(Route route) {
        Set<HttpMethod> methods = route.methods().isEmpty() ? EnumSet.noneOf(HttpMethod.class) : route.methods();
        for (String methodKey : methods.isEmpty() ? List.of("*") : methods.stream().map(Enum::name).toList()) {
            String key = methodKey + " " + route.pattern().shape() + " consumes=" + route.consumes();
            Route existing = byKey.putIfAbsent(key, route);
            if (existing != null) {
                throw new IllegalStateException("Ambiguous mapping: " + methodKey + " " + route.pattern() + " on "
                        + route.handler() + " matches the same requests as " + existing.pattern() + " on "
                        + existing.handler());
            }
        }
        routes.add(route);
    }

    public List<Route> routes() {
        return List.copyOf(routes);
    }

    /** The outcome of a lookup: a match, or the status that explains why there is none. */
    public sealed interface Lookup {
    }

    public record Found(Match match) implements Lookup {
    }

    /** No route for the path (404), so static resources may still apply. */
    public record NoPath() implements Lookup {
    }

    public record WrongMethod(Set<HttpMethod> allowed) implements Lookup {
    }

    public record WrongContentType(Set<String> supported) implements Lookup {
    }

    public Lookup lookup(HttpRequest request) {
        record Candidate(Route route, Map<String, String> variables) {
        }
        List<Candidate> byPath = new ArrayList<>();
        for (Route route : routes) {
            route.pattern().match(request.path()).ifPresent(variables -> byPath.add(new Candidate(route, variables)));
        }
        if (byPath.isEmpty()) {
            return new NoPath();
        }
        HttpMethod effective = request.method();
        List<Candidate> byMethod = byPath.stream().filter(c -> handles(c.route(), effective)).toList();
        if (byMethod.isEmpty() && effective == HttpMethod.HEAD) {
            byMethod = byPath.stream().filter(c -> handles(c.route(), HttpMethod.GET)).toList(); // HEAD falls back to GET
        }
        if (byMethod.isEmpty()) {
            Set<HttpMethod> allowed = new LinkedHashSet<>();
            byPath.forEach(c -> allowed.addAll(c.route().methods()));
            if (allowed.contains(HttpMethod.GET)) {
                allowed.add(HttpMethod.HEAD);
            }
            return new WrongMethod(allowed);
        }
        String mediaType = request.headers().mediaType();
        boolean hasBody = request.body().length > 0;
        List<Candidate> byContent = byMethod.stream()
                .filter(c -> !hasBody && mediaType == null || c.route().acceptsContentType(mediaType)).toList();
        if (byContent.isEmpty()) {
            Set<String> supported = byMethod.stream().flatMap(c -> c.route().consumes().stream())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            return new WrongContentType(supported);
        }
        Candidate best = byContent.stream()
                .min(Comparator.<Candidate, PathPattern>comparing(c -> c.route().pattern())
                        .thenComparing(c -> c.route().methods().isEmpty() ? 1 : 0))
                .orElseThrow();
        return new Found(new Match(best.route(), best.variables()));
    }

    private static boolean handles(Route route, HttpMethod method) {
        return route.methods().isEmpty() || route.methods().contains(method);
    }

    /** A readable dump of the table, one line per route, used in the startup report. */
    public List<String> describe() {
        return routes.stream().map(route -> (route.methods().isEmpty() ? "ANY" : route.methods().stream()
                        .map(Enum::name).collect(Collectors.joining("|"))) + " " + route.pattern() + " -> " + route.handler())
                .sorted().toList();
    }
}

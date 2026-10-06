package io.minispring.web.mvc;

import io.minispring.core.annotation.MergedAnnotations;
import io.minispring.core.beans.BeanDefinition;
import io.minispring.core.beans.BeanFactory;
import io.minispring.core.beans.ReflectionSupport;
import io.minispring.core.convert.ConversionService;
import io.minispring.web.annotation.Controller;
import io.minispring.web.annotation.ControllerAdvice;
import io.minispring.web.annotation.RequestMapping;
import io.minispring.web.annotation.PathVariable;
import io.minispring.web.http.HttpMethod;
import io.minispring.web.http.HttpRequest;
import io.minispring.web.http.HttpResponse;
import io.minispring.web.http.HttpStatus;
import io.minispring.web.http.WebServer;
import io.minispring.web.json.JsonMapper;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The front controller: every request goes through {@link #handle}, which is the whole request
 * life cycle in one readable method.
 *
 * <ol>
 *   <li><b>Route</b> - {@link RouteTable#lookup}. No path match: a static file or a 404; wrong method:
 *       405 with {@code Allow}; wrong content type: 415.</li>
 *   <li><b>Interceptors</b> - {@code preHandle} in order; any may end the request.</li>
 *   <li><b>Arguments</b> - one {@link HandlerMethodArgumentResolver} per parameter, chosen once at startup.</li>
 *   <li><b>Invoke</b> - the controller method, through the proxy if the bean has one.</li>
 *   <li><b>Return value</b> - the first {@link ReturnValueHandler} that supports it writes the response.</li>
 *   <li><b>Failure</b> - {@code @ExceptionHandler} methods (controller, then advice), else a JSON error.</li>
 *   <li><b>Interceptors</b> - {@code afterCompletion} in reverse order.</li>
 * </ol>
 *
 * <p>All wiring (scanning controllers, building the table, validating that every parameter has a
 * resolver, rejecting ambiguous mappings) happens in {@link #initialize()}, called when the server
 * starts, so a bad controller prevents startup instead of failing a request later.
 */
public final class DispatcherHandler implements WebServer.RequestHandler {

    private static final System.Logger LOG = System.getLogger(DispatcherHandler.class.getName());

    private final BeanFactory beanFactory;
    private final ConversionService conversion;
    private final JsonMapper json;
    private final RouteTable routes = new RouteTable();
    private final Map<HandlerMethod, List<HandlerMethodArgumentResolver>> resolvers = new IdentityHashMap<>();
    private final StaticResources staticResources;
    private List<HandlerInterceptor> interceptors = List.of();
    private ReturnValueHandlers returnValues;
    private ResponseBodyWriter bodyWriter;
    private ExceptionResolver exceptions;
    private volatile boolean initialized;

    public DispatcherHandler(BeanFactory beanFactory, ConversionService conversion, JsonMapper json) {
        this.beanFactory = beanFactory;
        this.conversion = conversion;
        this.json = json;
        this.staticResources = new StaticResources(Thread.currentThread().getContextClassLoader() != null
                ? Thread.currentThread().getContextClassLoader() : DispatcherHandler.class.getClassLoader());
    }

    // ---------------------------------------------------------------- startup

    /**
     * Collects controllers, advice, resolvers, handlers and interceptors from the container and builds
     * the routing table. Safe to call more than once.
     *
     * @throws IllegalStateException for ambiguous mappings, parameters nobody can resolve, or
     *                               {@code @PathVariable} names missing from the pattern
     */
    public synchronized void initialize() {
        if (initialized) {
            return;
        }
        bodyWriter = new ResponseBodyWriter(json);
        returnValues = new ReturnValueHandlers(List.copyOf(beanFactory.getBeansOfType(ReturnValueHandler.class).values()),
                bodyWriter);
        interceptors = List.copyOf(beanFactory.getBeansOfType(HandlerInterceptor.class).values());

        List<HandlerMethodArgumentResolver> all = new ArrayList<>(beanFactory.getBeansOfType(
                HandlerMethodArgumentResolver.class).values());
        all.add(new NamedValueResolver.PathVariables(conversion));
        all.add(new NamedValueResolver.RequestParams(conversion));
        all.add(new NamedValueResolver.RequestHeaders(conversion));
        all.add(new RequestBodyResolver(json));
        all.add(new RequestObjectResolver());

        List<Object> adviceBeans = new ArrayList<>();
        List<Class<?>> adviceClasses = new ArrayList<>();
        for (String name : beanFactory.getBeanNames()) {
            BeanDefinition definition = beanFactory.getBeanDefinition(name);
            Class<?> type = definition.beanClass();
            if (MergedAnnotations.isPresent(type, ControllerAdvice.class)) {
                adviceBeans.add(beanFactory.getBean(name));
                adviceClasses.add(type);
            }
        }
        exceptions = new ExceptionResolver(adviceBeans, adviceClasses);

        for (String name : beanFactory.getBeanNames()) {
            Class<?> type = beanFactory.getBeanDefinition(name).beanClass();
            if (MergedAnnotations.isPresent(type, Controller.class)) {
                registerController(beanFactory.getBean(name), type, all);
            }
        }
        initialized = true;
    }

    private void registerController(Object bean, Class<?> type, List<HandlerMethodArgumentResolver> available) {
        RequestMapping classMapping = MergedAnnotations.find(type, RequestMapping.class).orElse(null);
        List<String> prefixes = classMapping == null || classMapping.path().length == 0
                ? List.of("") : List.of(classMapping.path());
        for (Method method : ReflectionSupport.methodsSuperclassFirst(type)) {
            RequestMapping mapping = MergedAnnotations.find(method, RequestMapping.class).orElse(null);
            if (mapping == null) {
                continue;
            }
            HandlerMethod handler = new HandlerMethod(bean, type, method);
            resolvers.put(handler, resolversFor(handler, available));
            Set<HttpMethod> methods = EnumSet.noneOf(HttpMethod.class);
            methods.addAll(List.of(mapping.method().length > 0 ? mapping.method()
                    : classMapping != null ? classMapping.method() : new HttpMethod[0]));
            List<String> consumes = List.of(mapping.consumes().length > 0 ? mapping.consumes()
                    : classMapping != null ? classMapping.consumes() : new String[0]);
            List<String> paths = mapping.path().length == 0 ? List.of("") : List.of(mapping.path());
            for (String prefix : prefixes) {
                for (String path : paths) {
                    PathPattern pattern = PathPattern.parse(PathPattern.combine(prefix, path));
                    checkPathVariables(handler, pattern);
                    routes.register(new RouteTable.Route(pattern, methods, consumes, handler));
                }
            }
        }
    }

    private static List<HandlerMethodArgumentResolver> resolversFor(HandlerMethod handler,
                                                                    List<HandlerMethodArgumentResolver> available) {
        List<HandlerMethodArgumentResolver> chosen = new ArrayList<>();
        for (MethodParameter parameter : handler.parameters()) {
            HandlerMethodArgumentResolver found = available.stream().filter(r -> r.supports(parameter)).findFirst()
                    .orElseThrow(() -> new IllegalStateException("No argument resolver supports " + parameter.describe()
                            + ". Annotate it with @PathVariable, @RequestParam, @RequestHeader or @RequestBody, "
                            + "or register a HandlerMethodArgumentResolver bean for it"));
            chosen.add(found);
        }
        return chosen;
    }

    private static void checkPathVariables(HandlerMethod handler, PathPattern pattern) {
        for (MethodParameter parameter : handler.parameters()) {
            parameter.annotation(PathVariable.class).ifPresent(annotation -> {
                String name = annotation.value().isEmpty() ? parameter.name() : annotation.value();
                if (!pattern.variableNames().contains(name)) {
                    throw new IllegalStateException(handler + " declares @PathVariable '" + name
                            + "' but the pattern " + pattern + " only has " + pattern.variableNames());
                }
            });
        }
    }

    /** One line per route, for the startup report and diagnostics. */
    public List<String> describeRoutes() {
        initialize();
        return routes.describe();
    }

    // ---------------------------------------------------------------- request handling

    @Override
    public HttpResponse handle(HttpRequest request) {
        try {
            initialize();
            return dispatch(request);
        } catch (RuntimeException | Error e) {
            LOG.log(System.Logger.Level.ERROR, "Dispatcher failure for " + request.method() + " " + request.path(), e);
            return errorResponse(new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error"),
                    request);
        }
    }

    private HttpResponse dispatch(HttpRequest request) {
        RouteTable.Lookup lookup = routes.lookup(request);
        return switch (lookup) {
            case RouteTable.Found found -> invoke(request, found.match());
            case RouteTable.NoPath none -> staticResources.serve(request).orElseGet(() ->
                    errorResponse(new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "No handler for " + request.method() + " " + request.path()), request));
            case RouteTable.WrongMethod wrong -> request.method() == HttpMethod.OPTIONS
                    ? allowResponse(wrong)
                    : errorResponse(withAllow(new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED,
                            "Method " + request.method() + " is not supported for " + request.path()
                                    + "; supported: " + wrong.allowed()), wrong), request);
            case RouteTable.WrongContentType wrong -> {
                ResponseStatusException unsupported = new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                        "Content type '" + request.headers().mediaType() + "' is not supported; accepted: "
                                + wrong.supported());
                unsupported.headers().set("Accept", String.join(", ", wrong.supported()));
                yield errorResponse(unsupported, request);
            }
        };
    }

    private static ResponseStatusException withAllow(ResponseStatusException exception, RouteTable.WrongMethod wrong) {
        exception.headers().set("Allow", allowHeader(wrong));
        return exception;
    }

    private static String allowHeader(RouteTable.WrongMethod wrong) {
        Set<HttpMethod> allowed = EnumSet.of(HttpMethod.OPTIONS);
        allowed.addAll(wrong.allowed());
        return allowed.stream().map(Enum::name).collect(Collectors.joining(", "));
    }

    private HttpResponse allowResponse(RouteTable.WrongMethod wrong) {
        HttpResponse response = new HttpResponse().status(HttpStatus.NO_CONTENT);
        response.headers().set("Allow", allowHeader(wrong));
        return response;
    }

    private HttpResponse invoke(HttpRequest request, RouteTable.Match match) {
        HandlerMethod handler = match.route().handler();
        RequestContext context = new RequestContext(request, handler, match.pathVariables());
        HttpResponse response = new HttpResponse();
        List<HandlerInterceptor> entered = new ArrayList<>();
        Throwable failure = null;
        try {
            boolean proceed = true;
            for (HandlerInterceptor interceptor : interceptors) {
                if (!interceptor.preHandle(context, response)) {
                    proceed = false;
                    break;
                }
                entered.add(interceptor);
            }
            if (proceed) {
                Object[] arguments = new Object[handler.parameters().size()];
                List<HandlerMethodArgumentResolver> chosen = resolvers.get(handler);
                for (int i = 0; i < arguments.length; i++) {
                    arguments[i] = chosen.get(i).resolve(handler.parameters().get(i), context);
                }
                Object result = handler.invoke(arguments);
                returnValues.handle(result, handler, context, response);
            }
        } catch (Throwable thrown) {
            failure = thrown;
            response = failureResponse(thrown, handler, context);
        }
        for (HandlerInterceptor interceptor : entered.reversed()) {
            try {
                interceptor.afterCompletion(context, response, failure);
            } catch (Exception e) {
                LOG.log(System.Logger.Level.WARNING, "Interceptor " + interceptor.getClass().getName()
                        + " failed in afterCompletion", e);
            }
        }
        return response;
    }

    /** Lets an exception handler claim the failure; otherwise renders the default error. */
    private HttpResponse failureResponse(Throwable failure, HandlerMethod handler, RequestContext context) {
        HttpResponse response = new HttpResponse();
        try {
            if (exceptions.handle(failure, handler, context, response, returnValues)) {
                return response;
            }
        } catch (Exception handlerFailure) {
            handlerFailure.addSuppressed(failure);
            LOG.log(System.Logger.Level.ERROR, "Exception handler for " + failure.getClass().getName() + " failed",
                    handlerFailure);
            return render(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error", context.request());
        }
        if (failure instanceof ResponseStatusException status) {
            return errorResponse(status, context.request());
        }
        LOG.log(System.Logger.Level.WARNING, "Unhandled exception in " + handler, failure);
        return render(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error", context.request());
    }

    /** Errors raised before any handler is chosen: advice may still shape them, the default is JSON. */
    private HttpResponse errorResponse(ResponseStatusException error, HttpRequest request) {
        RequestContext context = new RequestContext(request, null, Map.of());
        HttpResponse response = new HttpResponse();
        try {
            if (exceptions != null && exceptions.handle(error, null, context, response, returnValues)) {
                return response;
            }
        } catch (Exception handlerFailure) {
            LOG.log(System.Logger.Level.ERROR, "Exception handler failed for " + error.status(), handlerFailure);
        }
        HttpResponse fallback = render(error.status(), error.getMessage(), request);
        error.headers().asMap().forEach((name, values) -> values.forEach(v -> fallback.headers().add(name, v)));
        return fallback;
    }

    private HttpResponse render(HttpStatus status, String message, HttpRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status.code());
        body.put("error", status.reason());
        body.put("message", message);
        body.put("path", request.path());
        HttpResponse response = new HttpResponse().status(status);
        response.headers().set("Content-Type", "application/json");
        response.body(json.writeValueAsBytes(body));
        return response;
    }
}

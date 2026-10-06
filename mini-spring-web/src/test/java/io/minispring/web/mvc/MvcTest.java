package io.minispring.web.mvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.minispring.core.annotation.Component;
import io.minispring.core.annotation.Configuration;
import io.minispring.core.annotation.Order;
import io.minispring.core.context.ApplicationContext;
import io.minispring.web.annotation.ControllerAdvice;
import io.minispring.web.annotation.DeleteMapping;
import io.minispring.web.annotation.ExceptionHandler;
import io.minispring.web.annotation.GetMapping;
import io.minispring.web.annotation.PathVariable;
import io.minispring.web.annotation.PostMapping;
import io.minispring.web.annotation.RequestBody;
import io.minispring.web.annotation.RequestHeader;
import io.minispring.web.annotation.RequestMapping;
import io.minispring.web.annotation.RequestParam;
import io.minispring.web.annotation.ResponseStatus;
import io.minispring.web.annotation.RestController;
import io.minispring.web.http.HttpMethod;
import io.minispring.web.http.HttpRequest;
import io.minispring.web.http.HttpResponse;
import io.minispring.web.http.HttpStatus;
import io.minispring.web.http.ResponseEntity;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The routing table, argument binding, return values, error mapping and interceptors, driven by
 * calling the dispatcher directly (no sockets) against real controllers wired by the container.
 */
class MvcTest {

    static final List<String> EVENTS = new CopyOnWriteArrayList<>();

    record OrderDto(long id, String item, int quantity) {
    }

    record Product(String name, long price) {
    }

    @Configuration
    @EnableWebMvc
    static class App {
    }

    // ---- controllers ------------------------------------------------------------------------------------

    @RestController
    @RequestMapping("/orders")
    static class OrderController {

        @GetMapping
        List<OrderDto> list() {
            return List.of(new OrderDto(1, "pen", 2));
        }

        @GetMapping("/{id}")
        OrderDto byId(@PathVariable long id) {
            if (id > 100) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no order " + id);
            }
            return new OrderDto(id, "pen", 1);
        }

        @GetMapping("/latest")
        String latest() {
            return "the literal route wins over /{id}";
        }

        @GetMapping("/{id}/items/{sku}")
        String item(@PathVariable("id") long orderId, @PathVariable String sku) {
            return orderId + ":" + sku;
        }

        @PostMapping
        ResponseEntity<OrderDto> create(@RequestBody OrderDto order) {
            return ResponseEntity.created(URI.create("/orders/" + order.id()), order);
        }

        @PostMapping("/batch")
        @ResponseStatus(HttpStatus.ACCEPTED)
        String batch(@RequestBody List<OrderDto> orders) {
            return orders.size() + " x " + orders.get(0).getClass().getSimpleName();
        }

        @PostMapping(value = "/xml-only", consumes = "application/xml")
        String xml() {
            return "xml";
        }

        @GetMapping("/search")
        Map<String, Object> search(@RequestParam List<Long> ids, @RequestParam Optional<Integer> page,
                                   @RequestParam(defaultValue = "10") int size,
                                   @RequestParam(required = false) String q,
                                   @RequestHeader("X-Tenant") String tenant) {
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("ids", ids);
            result.put("idType", ids.get(0).getClass().getSimpleName());
            result.put("page", page.orElse(-1));
            result.put("size", size);
            result.put("q", q);
            result.put("tenant", tenant);
            return result;
        }

        @DeleteMapping("/{id}")
        void remove(@PathVariable long id) {
            EVENTS.add("removed " + id);
        }

        @GetMapping("/illegal")
        String illegal() {
            throw new IllegalArgumentException("bad argument");
        }

        @GetMapping("/number")
        String number() {
            return String.valueOf(Integer.parseInt("x")); // NumberFormatException extends IllegalArgumentException
        }

        @GetMapping("/boom")
        String boom() {
            throw new IllegalStateException("kaput");
        }

        @GetMapping("/local")
        String local() {
            throw new UnsupportedOperationException("local");
        }

        @ExceptionHandler(UnsupportedOperationException.class)
        @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
        Map<String, String> unsupported(UnsupportedOperationException e) {
            return Map.of("handledBy", "controller", "message", e.getMessage());
        }

        @GetMapping("/files/**")
        String files(HttpRequest request) {
            return request.path();
        }

        @GetMapping("/whoami")
        String whoami(@CurrentUser String user) {
            return user;
        }

        @GetMapping("/csv")
        Csv csv() {
            return new Csv(List.of("a,b", "1,2"));
        }
    }

    /** A custom parameter kind, resolved by an application-provided resolver bean. */
    @Target(ElementType.PARAMETER)
    @Retention(RetentionPolicy.RUNTIME)
    @interface CurrentUser {
    }

    @Component
    static class CurrentUserResolver implements HandlerMethodArgumentResolver {
        @Override
        public boolean supports(MethodParameter parameter) {
            return parameter.annotation(CurrentUser.class).isPresent();
        }

        @Override
        public Object resolve(MethodParameter parameter, RequestContext context) {
            String user = context.request().headers().first("X-User");
            return user == null ? "anonymous" : user;
        }
    }

    record Csv(List<String> lines) {
    }

    /** A custom return value kind. */
    @Component
    static class CsvReturnHandler implements ReturnValueHandler {
        @Override
        public boolean supports(HandlerMethod handler, Object value) {
            return value instanceof Csv;
        }

        @Override
        public void handle(Object value, HandlerMethod handler, RequestContext context, HttpResponse response) {
            response.headers().set("Content-Type", "text/csv");
            response.body(String.join("\n", ((Csv) value).lines()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    /** A generic base controller: T is only known from the subclass declaration. */
    abstract static class CrudController<T, ID> {
        @PostMapping
        T save(@RequestBody T entity) {
            return entity;
        }

        @PostMapping("/all")
        List<T> saveAll(@RequestBody List<T> entities) {
            return entities;
        }

        @GetMapping("/{id}")
        String find(@PathVariable ID id) {
            return id.getClass().getSimpleName() + ":" + id;
        }
    }

    @RestController
    @RequestMapping("/products")
    static class ProductController extends CrudController<Product, Long> {
    }

    @ControllerAdvice
    static class GlobalAdvice {

        @ExceptionHandler(IllegalArgumentException.class)
        ResponseEntity<Map<String, String>> badArgument(IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "handledBy", "IllegalArgument", "message", String.valueOf(e.getMessage())));
        }

        @ExceptionHandler(IllegalStateException.class)
        ResponseEntity<Map<String, String>> illegalState(IllegalStateException e, HttpRequest request) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("handledBy", "IllegalState",
                    "path", request.path()));
        }
    }

    @Component
    @Order(1)
    static class TraceInterceptor implements HandlerInterceptor {
        @Override
        public boolean preHandle(RequestContext context, HttpResponse response) {
            EVENTS.add("pre1 " + context.handler());
            response.headers().set("X-Trace", "1");
            return true;
        }

        @Override
        public void afterCompletion(RequestContext context, HttpResponse response, Throwable failure) {
            EVENTS.add("after1 " + response.status() + (failure == null ? "" : " " + failure.getClass().getSimpleName()));
        }
    }

    @Component
    @Order(2)
    static class GateInterceptor implements HandlerInterceptor {
        @Override
        public boolean preHandle(RequestContext context, HttpResponse response) {
            EVENTS.add("pre2");
            if ("deny".equals(context.request().headers().first("X-Gate"))) {
                response.status(HttpStatus.FORBIDDEN);
                return false;
            }
            return true;
        }

        @Override
        public void afterCompletion(RequestContext context, HttpResponse response, Throwable failure) {
            EVENTS.add("after2");
        }
    }

    // ---- harness ------------------------------------------------------------------------------------------------

    private ApplicationContext context;
    private DispatcherHandler dispatcher;

    @BeforeEach
    void start() {
        EVENTS.clear();
        context = ApplicationContext.builder()
                .register(App.class, OrderController.class, ProductController.class, GlobalAdvice.class,
                        CurrentUserResolver.class, CsvReturnHandler.class, TraceInterceptor.class, GateInterceptor.class)
                .property("server.port", "0").property("server.host", "127.0.0.1")
                .build();
        dispatcher = context.getBean(DispatcherHandler.class);
    }

    @AfterEach
    void stop() {
        context.close();
    }

    private static Object tree(String json) {
        return new io.minispring.web.json.JsonMapper().readTree(json);
    }

    private HttpResponse send(HttpMethod method, String target) {
        return dispatcher.handle(HttpRequest.of(method, target));
    }

    private HttpResponse send(HttpRequest request) {
        return dispatcher.handle(request);
    }

    // ---- routing ----------------------------------------------------------------------------------------------

    @Test
    void routesByMethodAndPathAndBindsPathVariablesWithConversion() {
        HttpResponse response = send(HttpMethod.GET, "/orders/7");
        assertEquals(200, response.status());
        assertEquals("application/json", response.headers().first("Content-Type"));
        assertEquals("{\"id\":7,\"item\":\"pen\",\"quantity\":1}", response.bodyAsString());
        assertEquals("[{\"id\":1,\"item\":\"pen\",\"quantity\":2}]", send(HttpMethod.GET, "/orders").bodyAsString());
        assertEquals("7:A 1", send(HttpMethod.GET, "/orders/7/items/A%201").bodyAsString());
    }

    @Test
    void aLiteralRouteBeatsAVariableRouteRegardlessOfDeclarationOrder() {
        assertEquals("the literal route wins over /{id}", send(HttpMethod.GET, "/orders/latest").bodyAsString());
    }

    @Test
    void anUnconvertiblePathVariableIsA400NamingTheVariable() {
        HttpResponse response = send(HttpMethod.GET, "/orders/abc");
        assertEquals(400, response.status());
        assertTrue(response.bodyAsString().contains("path variable 'id'"), response.bodyAsString());
    }

    @Test
    void noMatchingPathIs404WithAJsonErrorBody() {
        HttpResponse response = send(HttpMethod.GET, "/nothing/here");
        assertEquals(404, response.status());
        assertEquals("{\"status\":404,\"error\":\"Not Found\",\"message\":\"No handler for GET /nothing/here\","
                + "\"path\":\"/nothing/here\"}", response.bodyAsString());
    }

    @Test
    void aKnownPathWithAnUnsupportedMethodIs405WithAnAllowHeader() {
        HttpResponse response = send(HttpMethod.PUT, "/orders/7");
        assertEquals(405, response.status());
        assertEquals("GET, HEAD, DELETE, OPTIONS", response.headers().first("Allow"));
    }

    @Test
    void optionsAnswersWithAllowAndHeadFallsBackToGet() {
        HttpResponse options = send(HttpMethod.OPTIONS, "/orders/7");
        assertEquals(204, options.status());
        assertEquals("GET, HEAD, DELETE, OPTIONS", options.headers().first("Allow"));
        HttpResponse head = send(HttpMethod.HEAD, "/orders/7");
        assertEquals(200, head.status(), "HEAD is served by the GET handler; the server drops the body");
    }

    @Test
    void aRouteThatConsumesAnotherContentTypeIs415() {
        HttpResponse response = send(HttpRequest.of(HttpMethod.POST, "/orders/xml-only")
                .withHeader("Content-Type", "application/json").withBody("{}"));
        assertEquals(415, response.status());
        assertEquals("application/xml", response.headers().first("Accept"));
        assertEquals("xml", send(HttpRequest.of(HttpMethod.POST, "/orders/xml-only")
                .withHeader("Content-Type", "application/xml; charset=utf-8").withBody("<a/>")).bodyAsString());
    }

    @Test
    void doubleWildcardRoutesCatchTheRest() {
        assertEquals("/orders/files/a/b/c.txt", send(HttpMethod.GET, "/orders/files/a/b/c.txt").bodyAsString());
    }

    // ---- arguments ---------------------------------------------------------------------------------------------

    @Test
    void bindsRequestParametersHeadersDefaultsOptionalsAndListsWithElementTypes() {
        HttpResponse response = send(HttpRequest.of(HttpMethod.GET, "/orders/search?ids=3&ids=5&page=2&q=hello%20world")
                .withHeader("X-Tenant", "acme"));
        assertEquals("{\"ids\":[3,5],\"idType\":\"Long\",\"page\":2,\"size\":10,\"q\":\"hello world\","
                + "\"tenant\":\"acme\"}", response.bodyAsString());
        HttpResponse minimal = send(HttpRequest.of(HttpMethod.GET, "/orders/search?ids=1").withHeader("X-Tenant", "t"));
        assertEquals("{\"ids\":[1],\"idType\":\"Long\",\"page\":-1,\"size\":10,\"q\":null,\"tenant\":\"t\"}",
                minimal.bodyAsString(), "absent Optional is empty, absent optional String is null, default applies");
    }

    @Test
    void missingRequiredInputsAreA400ThatNamesThem() {
        HttpResponse noHeader = send(HttpMethod.GET, "/orders/search?ids=1");
        assertEquals(400, noHeader.status());
        assertTrue(noHeader.bodyAsString().contains("request header 'X-Tenant'"), noHeader.bodyAsString());
        HttpResponse noIds = send(HttpRequest.of(HttpMethod.GET, "/orders/search").withHeader("X-Tenant", "t"));
        assertTrue(noIds.bodyAsString().contains("request parameter 'ids'"), noIds.bodyAsString());
        HttpResponse badNumber = send(HttpRequest.of(HttpMethod.GET, "/orders/search?ids=1&size=big")
                .withHeader("X-Tenant", "t"));
        assertEquals(400, badNumber.status());
        assertTrue(badNumber.bodyAsString().contains("'size'"), badNumber.bodyAsString());
    }

    @Test
    void bindsAJsonBodyToTheDeclaredGenericParameterType() {
        HttpResponse single = send(HttpRequest.of(HttpMethod.POST, "/orders")
                .withJson("{\"id\":9,\"item\":\"ink\",\"quantity\":3}"));
        assertEquals(201, single.status());
        assertEquals("/orders/9", single.headers().first("Location"));
        assertEquals("{\"id\":9,\"item\":\"ink\",\"quantity\":3}", single.bodyAsString());

        HttpResponse batch = send(HttpRequest.of(HttpMethod.POST, "/orders/batch")
                .withJson("[{\"id\":1,\"item\":\"a\",\"quantity\":1},{\"id\":2,\"item\":\"b\",\"quantity\":2}]"));
        assertEquals(202, batch.status(), "@ResponseStatus");
        assertEquals("2 x OrderDto", batch.bodyAsString(), "elements are OrderDto, not LinkedHashMap");
    }

    @Test
    void aGenericBaseControllerSeesItsTypeVariablesBoundBySubclass() {
        HttpResponse saved = send(HttpRequest.of(HttpMethod.POST, "/products").withJson("{\"name\":\"tea\",\"price\":4}"));
        assertEquals("{\"name\":\"tea\",\"price\":4}", saved.bodyAsString());
        HttpResponse many = send(HttpRequest.of(HttpMethod.POST, "/products/all")
                .withJson("[{\"name\":\"a\",\"price\":1},{\"name\":\"b\",\"price\":2}]"));
        assertEquals("[{\"name\":\"a\",\"price\":1},{\"name\":\"b\",\"price\":2}]", many.bodyAsString());
        assertEquals("Long:12", send(HttpMethod.GET, "/products/12").bodyAsString(),
                "@PathVariable ID id is converted to Long because ProductController binds ID=Long");
    }

    @Test
    void bodyProblemsAreDistinguished() {
        HttpResponse wrongType = send(HttpRequest.of(HttpMethod.POST, "/orders")
                .withHeader("Content-Type", "text/plain").withBody("hello"));
        assertEquals(415, wrongType.status());
        HttpResponse malformed = send(HttpRequest.of(HttpMethod.POST, "/orders").withJson("{\"id\":"));
        assertEquals(400, malformed.status());
        assertTrue(malformed.bodyAsString().contains("Invalid request body"), malformed.bodyAsString());
        HttpResponse wrongField = send(HttpRequest.of(HttpMethod.POST, "/orders")
                .withJson("{\"id\":\"x\",\"item\":\"a\",\"quantity\":1}"));
        assertEquals(400, wrongField.status());
        assertTrue(wrongField.bodyAsString().contains("$.id"), wrongField.bodyAsString());
        HttpResponse empty = send(HttpMethod.POST, "/orders");
        assertEquals(400, empty.status());
        assertTrue(empty.bodyAsString().contains("body is missing"), empty.bodyAsString());
    }

    @Test
    void customArgumentResolversAndReturnValueHandlersArePluggable() {
        assertEquals("alice", send(HttpRequest.of(HttpMethod.GET, "/orders/whoami").withHeader("X-User", "alice"))
                .bodyAsString());
        assertEquals("anonymous", send(HttpMethod.GET, "/orders/whoami").bodyAsString());
        HttpResponse csv = send(HttpMethod.GET, "/orders/csv");
        assertEquals("text/csv", csv.headers().first("Content-Type"));
        assertEquals("a,b\n1,2", csv.bodyAsString());
    }

    @Test
    void voidHandlersAnswer200WithoutABody() {
        HttpResponse response = send(HttpMethod.DELETE, "/orders/5");
        assertEquals(200, response.status());
        assertEquals(0, response.body().length);
        assertTrue(EVENTS.contains("removed 5"));
    }

    // ---- exception handling ------------------------------------------------------------------------------------

    @Test
    void theClosestExceptionTypeWinsAndLocalHandlersBeatAdvice() {
        HttpResponse illegal = send(HttpMethod.GET, "/orders/illegal");
        assertEquals(400, illegal.status());
        assertEquals(tree("{\"message\":\"bad argument\",\"handledBy\":\"IllegalArgument\"}"), tree(illegal.bodyAsString()));

        HttpResponse subclass = send(HttpMethod.GET, "/orders/number");
        assertEquals(400, subclass.status(), "NumberFormatException extends IllegalArgumentException");

        HttpResponse general = send(HttpMethod.GET, "/orders/boom");
        assertEquals(409, general.status());
        assertEquals(tree("{\"path\":\"/orders/boom\",\"handledBy\":\"IllegalState\"}"), tree(general.bodyAsString()));

        HttpResponse local = send(HttpMethod.GET, "/orders/local");
        assertEquals(422, local.status(), "@ResponseStatus on the handler method");
        assertEquals(tree("{\"message\":\"local\",\"handledBy\":\"controller\"}"), tree(local.bodyAsString()));
    }

    @Test
    void aResponseStatusExceptionKeepsItsStatusUnlessAHandlerNamesIt() {
        HttpResponse notFound = send(HttpMethod.GET, "/orders/101");
        assertEquals(404, notFound.status());
        assertTrue(notFound.bodyAsString().contains("no order 101"), notFound.bodyAsString());
    }

    // ---- interceptors ------------------------------------------------------------------------------------------

    @Test
    void interceptorsWrapTheHandlerInOrderAndUnwindInReverse() {
        HttpResponse response = send(HttpMethod.GET, "/orders/1");
        assertEquals("1", response.headers().first("X-Trace"));
        assertEquals(List.of("pre1 OrderController.byId()", "pre2", "after2", "after1 200"), EVENTS);
    }

    @Test
    void anInterceptorCanShortCircuitAndOnlyThoseThatEnteredAreUnwound() {
        HttpResponse response = send(HttpRequest.of(HttpMethod.GET, "/orders/1").withHeader("X-Gate", "deny"));
        assertEquals(403, response.status());
        assertEquals(List.of("pre1 OrderController.byId()", "pre2", "after1 403"), EVENTS,
                "the gate said no: its own afterCompletion does not run, the handler never ran");
    }

    @Test
    void afterCompletionSeesTheFailureEvenWhenAHandlerTurnedItIntoAResponse() {
        send(HttpMethod.GET, "/orders/boom");
        assertEquals("after1 409 IllegalStateException", EVENTS.get(EVENTS.size() - 1));
    }

    // ---- startup validation -------------------------------------------------------------------------------------

    private static Throwable startupFailure(Class<?>... controllers) {
        return assertThrows(RuntimeException.class, () -> ApplicationContext.builder().register(App.class)
                .register(controllers).property("server.port", "0").property("server.host", "127.0.0.1").build());
    }

    private static String messages(Throwable failure) {
        StringBuilder all = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            all.append(t.getMessage()).append(" | ");
        }
        return all.toString();
    }

    @RestController
    static class Ambiguous {
        @GetMapping("/same/{id}")
        String one(@PathVariable String id) {
            return id;
        }

        @GetMapping("/same/{name}")
        String two(@PathVariable String name) {
            return name;
        }
    }

    @RestController
    static class Unresolvable {
        @GetMapping("/x")
        String x(java.util.UUID noAnnotation) {
            return "x";
        }
    }

    @RestController
    static class WrongVariable {
        @GetMapping("/x/{id}")
        String x(@PathVariable String other) {
            return "x";
        }
    }

    @Test
    void startupFailsWithAClearMessageForBrokenControllers() {
        assertTrue(messages(startupFailure(Ambiguous.class)).contains("Ambiguous mapping: GET /same/{"),
                messages(startupFailure(Ambiguous.class)));
        String unresolvable = messages(startupFailure(Unresolvable.class));
        assertTrue(unresolvable.contains("No argument resolver supports Unresolvable.x(parameter 0: UUID)"), unresolvable);
        String wrongVariable = messages(startupFailure(WrongVariable.class));
        assertTrue(wrongVariable.contains("@PathVariable 'other'") && wrongVariable.contains("[id]"), wrongVariable);
    }

    @Test
    void theRouteTableIsDescribedForTheStartupReport() {
        List<String> lines = dispatcher.describeRoutes();
        assertTrue(lines.contains("GET /orders/{id} -> OrderController.byId()"), lines.toString());
        assertTrue(lines.contains("POST /products -> ProductController.save()"), lines.toString());
        List<String> sorted = new ArrayList<>(lines);
        sorted.sort(null);
        assertEquals(sorted, lines);
    }
}

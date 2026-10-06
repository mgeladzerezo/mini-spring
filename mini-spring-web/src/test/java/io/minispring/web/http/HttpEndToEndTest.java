package io.minispring.web.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.minispring.aop.EnableAspects;
import io.minispring.aop.aspects.MethodTimings;
import io.minispring.aop.aspects.Timed;
import io.minispring.aop.proxy.GeneratedProxy;
import io.minispring.core.annotation.Configuration;
import io.minispring.core.context.ApplicationContext;
import io.minispring.web.annotation.GetMapping;
import io.minispring.web.annotation.PostMapping;
import io.minispring.web.annotation.RequestBody;
import io.minispring.web.annotation.RestController;
import io.minispring.web.mvc.EnableWebMvc;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Real sockets: the JDK server, virtual threads, static files, and a controller wrapped by an AOP proxy. */
class HttpEndToEndTest {

    record Item(String name, int qty) {
    }

    @Configuration
    @EnableWebMvc
    @EnableAspects
    static class App {
    }

    @RestController
    static class ItemController {
        @GetMapping("/thread")
        String thread() {
            return Thread.currentThread().isVirtual() ? "virtual" : "platform";
        }

        @Timed
        @PostMapping("/items")
        List<Item> echo(@RequestBody List<Item> items) {
            return items;
        }
    }

    private static ApplicationContext context;
    private static HttpClient client;
    private static String base;

    @BeforeAll
    static void start() {
        context = ApplicationContext.builder().register(App.class, ItemController.class)
                .property("server.port", "0").property("server.host", "127.0.0.1").build();
        base = "http://127.0.0.1:" + context.getBean(WebServer.class).port();
        client = HttpClient.newHttpClient();
    }

    @AfterAll
    static void stop() {
        context.close();
        client.close();
    }

    private static java.net.http.HttpResponse<String> get(String path) throws Exception {
        return client.send(java.net.http.HttpRequest.newBuilder(URI.create(base + path)).build(), BodyHandlers.ofString());
    }

    @Test
    void requestsRunOnVirtualThreads() throws Exception {
        assertEquals("virtual", get("/thread").body());
    }

    @Test
    void aGenericJsonBodyRoundTripsThroughAnAopProxiedController() throws Exception {
        assertTrue(context.getBean(ItemController.class) instanceof GeneratedProxy, "the controller is a generated subclass proxy");
        var response = client.send(java.net.http.HttpRequest.newBuilder(URI.create(base + "/items"))
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString("[{\"name\":\"a\",\"qty\":1},{\"name\":\"b\",\"qty\":2}]")).build(),
                BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("[{\"name\":\"a\",\"qty\":1},{\"name\":\"b\",\"qty\":2}]", response.body());
        assertEquals(1, context.getBean(MethodTimings.class).snapshot().values().stream()
                .mapToLong(t -> t.count()).sum(), "@Timed advice ran on the handler");
    }

    @Test
    void servesStaticFilesWithTypesAndRefusesTraversal() throws Exception {
        var index = get("/");
        assertEquals(200, index.statusCode());
        assertEquals("text/html; charset=utf-8", index.headers().firstValue("Content-Type").orElseThrow());
        assertEquals("text/javascript; charset=utf-8", get("/app.js").headers().firstValue("Content-Type").orElseThrow());
        assertEquals(404, get("/missing.css").statusCode());
        assertEquals(404, get("/%2e%2e/%2e%2e/pom.xml").statusCode());
        assertEquals(404, get("/..%5C..%5Cpom.xml").statusCode());
    }

    @Test
    void errorsUseTheStatusLineAndJsonBody() throws Exception {
        var notFound = get("/api/none");
        assertEquals(404, notFound.statusCode());
        assertTrue(notFound.body().contains("\"status\":404"));
        var wrongMethod = client.send(java.net.http.HttpRequest.newBuilder(URI.create(base + "/thread"))
                .POST(BodyPublishers.noBody()).build(), BodyHandlers.ofString());
        assertEquals(405, wrongMethod.statusCode());
        assertEquals("GET, HEAD, OPTIONS", wrongMethod.headers().firstValue("Allow").orElseThrow());
    }

    @Test
    void headReturnsHeadersWithoutABody() throws Exception {
        var head = client.send(java.net.http.HttpRequest.newBuilder(URI.create(base + "/thread"))
                .method("HEAD", BodyPublishers.noBody()).build(), BodyHandlers.ofString());
        assertEquals(200, head.statusCode());
        assertEquals("", head.body());
    }
}

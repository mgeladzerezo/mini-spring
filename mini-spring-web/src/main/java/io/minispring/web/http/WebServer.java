package io.minispring.web.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.minispring.core.context.Lifecycle;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The network edge: the JDK's {@code com.sun.net.httpserver} with one virtual thread per request.
 * Handlers may therefore block (JDBC, say) without a thread-pool size to tune; and because a
 * transaction is bound to the executing thread, a request's transaction lives and dies on its own
 * virtual thread.
 *
 * <p>This class only translates between the JDK's exchange objects and {@link HttpRequest} /
 * {@link HttpResponse}; everything else happens in the {@link RequestHandler}. It is a
 * {@link Lifecycle}, so the container starts it after all beans exist and stops it before any bean
 * is destroyed, which lets in-flight requests finish against live beans.
 */
public final class WebServer implements Lifecycle {

    /** What the server calls for each request; must not throw. */
    @FunctionalInterface
    public interface RequestHandler {
        HttpResponse handle(HttpRequest request);
    }

    private static final System.Logger LOG = System.getLogger(WebServer.class.getName());
    private static final int MAX_BODY_BYTES = 10 * 1024 * 1024;

    private final String host;
    private final int requestedPort;
    private final RequestHandler handler;
    private final Runnable beforeStart;
    private HttpServer server;
    private ExecutorService executor;

    /**
     * @param beforeStart runs inside {@link #start()} before the port is bound, so that a broken
     *                    configuration fails startup instead of failing the first request
     */
    public WebServer(String host, int port, RequestHandler handler, Runnable beforeStart) {
        this.host = host;
        this.requestedPort = port;
        this.handler = handler;
        this.beforeStart = beforeStart;
    }

    @Override
    public synchronized void start() {
        if (server != null) {
            return;
        }
        beforeStart.run();
        try {
            server = HttpServer.create(new InetSocketAddress(host, requestedPort), 0);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot bind " + host + ":" + requestedPort, e);
        }
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", this::exchange);
        server.start();
        LOG.log(System.Logger.Level.INFO, "Web server listening on {0}:{1}", host, String.valueOf(port()));
    }

    @Override
    public synchronized void stop() {
        if (server == null) {
            return;
        }
        server.stop(1);
        executor.close();
        server = null;
    }

    /** The bound port; useful when started with port 0. */
    public int port() {
        return server.getAddress().getPort();
    }

    private void exchange(HttpExchange exchange) throws IOException {
        try (exchange) {
            HttpResponse response;
            HttpMethod method = HttpMethod.resolve(exchange.getRequestMethod());
            if (method == null) {
                response = new HttpResponse().status(HttpStatus.METHOD_NOT_ALLOWED);
            } else {
                byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
                if (body.length > MAX_BODY_BYTES) {
                    response = new HttpResponse().status(HttpStatus.PAYLOAD_TOO_LARGE);
                } else {
                    response = handler.handle(toRequest(exchange, method, body));
                }
            }
            write(exchange, response);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "Unhandled failure outside the dispatcher", e);
            exchange.sendResponseHeaders(500, -1);
        }
    }

    private static HttpRequest toRequest(HttpExchange exchange, HttpMethod method, byte[] body) {
        HttpHeaders headers = new HttpHeaders();
        exchange.getRequestHeaders().forEach((name, values) -> values.forEach(value -> headers.add(name, value)));
        String rawQuery = exchange.getRequestURI().getRawQuery();
        return new HttpRequest(method, exchange.getRequestURI().getRawPath(), HttpRequest.parseQuery(rawQuery),
                headers, body);
    }

    private static void write(HttpExchange exchange, HttpResponse response) throws IOException {
        response.headers().asMap().forEach((name, values) -> values.forEach(value ->
                exchange.getResponseHeaders().add(name, value)));
        byte[] body = response.body();
        boolean head = "HEAD".equals(exchange.getRequestMethod());
        boolean bodyless = response.status() == 204 || response.status() == 304 || response.status() < 200;
        if (head || bodyless || body.length == 0) {
            if (head && body.length > 0) {
                exchange.getResponseHeaders().set("Content-Length", String.valueOf(body.length));
            }
            exchange.sendResponseHeaders(response.status(), -1);
            return;
        }
        exchange.sendResponseHeaders(response.status(), body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    /** The server's own view of what it was asked for, for diagnostics. */
    @Override
    public String toString() {
        return "WebServer[" + host + ":" + requestedPort + (server == null ? ", stopped" : ", port " + port()) + "]";
    }
}

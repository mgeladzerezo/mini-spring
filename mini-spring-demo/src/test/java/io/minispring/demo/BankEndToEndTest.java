package io.minispring.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.minispring.aop.proxy.GeneratedProxy;
import io.minispring.core.context.ApplicationContext;
import io.minispring.demo.service.TransferService;
import io.minispring.web.http.WebServer;
import io.minispring.web.json.JsonMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/** Boots the whole bank on a random port and drives it over HTTP. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BankEndToEndTest {

    private static final JsonMapper JSON = new JsonMapper();
    private static ApplicationContext context;
    private static HttpClient client;
    private static String base;

    @BeforeAll
    static void boot() {
        context = ApplicationContext.builder().register(BankApplication.class).scan("io.minispring.demo")
                .property("server.port", "0").property("server.host", "127.0.0.1")
                .property("bank.db.url", "jdbc:h2:mem:e2e;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
                .property("mini.startup-report", "false").build();
        base = "http://127.0.0.1:" + context.getBean(WebServer.class).port();
        client = HttpClient.newHttpClient();
    }

    @AfterAll
    static void shutdown() {
        client.close();
        context.close();
    }

    private static HttpResponse<String> call(String method, String path, String json) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path));
        if (json == null) {
            request.method(method, BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(json));
        }
        return client.send(request.build(), BodyHandlers.ofString());
    }

    private static BigDecimal balance(long id) throws Exception {
        Map<?, ?> account = (Map<?, ?>) JSON.readTree(call("GET", "/api/accounts/" + id, null).body());
        return new BigDecimal(account.get("balance").toString());
    }

    private static HttpResponse<String> transfer(long from, long to, String amount) throws Exception {
        return call("POST", "/api/transfers", "{\"fromId\":" + from + ",\"toId\":" + to + ",\"amount\":" + amount + "}");
    }

    @Test
    @Order(1)
    void seededAccountsAreListed() throws Exception {
        var response = call("GET", "/api/accounts", null);
        assertEquals(200, response.statusCode());
        List<?> accounts = (List<?>) JSON.readTree(response.body());
        assertEquals(3, accounts.size());
        assertEquals(0, new BigDecimal("1000.00").compareTo(balance(1)));
    }

    @Test
    @Order(2)
    void aTransferMovesMoneyAndIsAudited() throws Exception {
        var response = transfer(1, 2, "250.50");
        assertEquals(201, response.statusCode());
        assertEquals(0, new BigDecimal("749.50").compareTo(balance(1)));
        assertEquals(0, new BigDecimal("1250.50").compareTo(balance(2)));
        assertTrue(call("GET", "/api/transfers", null).body().contains("\"amount\":250.50"));
        assertTrue(call("GET", "/api/audit", null).body().contains("250.50 from account 1 to 2"),
                "the @EventListener received the TransferCompleted event");
    }

    @Test
    @Order(3)
    void insufficientFundsIsA422AndChangesNothing() throws Exception {
        BigDecimal before1 = balance(1);
        BigDecimal before3 = balance(3);
        var response = transfer(1, 3, "999999");
        assertEquals(422, response.statusCode());
        assertTrue(response.body().contains("does not have enough funds"), response.body());
        assertEquals(0, before1.compareTo(balance(1)));
        assertEquals(0, before3.compareTo(balance(3)));
    }

    @Test
    @Order(4)
    void aFailureAfterTheDebitRollsTheDebitBack() throws Exception {
        BigDecimal before = balance(1);
        var response = transfer(1, 777, "10");
        assertEquals(404, response.statusCode(), "the target does not exist");
        assertEquals(0, before.compareTo(balance(1)),
                "the debit was executed before the failure and must have been rolled back");
    }

    @Test
    @Order(5)
    void invalidInputIsA400() throws Exception {
        assertEquals(400, transfer(1, 2, "-5").statusCode());
        assertEquals(400, transfer(1, 1, "5").statusCode());
        assertEquals(400, call("POST", "/api/transfers", "{\"fromId\":\"x\"}").statusCode());
        assertEquals(400, call("POST", "/api/accounts", "{\"owner\":\" \",\"openingBalance\":1}").statusCode());
        assertEquals(404, call("GET", "/api/accounts/999", null).statusCode());
        assertEquals(201, call("POST", "/api/accounts", "{\"owner\":\"New\",\"openingBalance\":5}").statusCode());
    }

    @Test
    @Order(6)
    void concurrentTransfersNeitherCreateNorDestroyMoney() throws Exception {
        TransferService service = context.getBean(TransferService.class);
        assertInstanceOf(GeneratedProxy.class, service, "transactions run through the generated subclass proxy");
        BigDecimal totalBefore = service.totalBalance();
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 120; i++) {
                int n = i;
                futures.add(pool.submit(() -> {
                    long from = 1 + n % 3;
                    long to = 1 + (n + 1 + n / 3 % 2) % 3;
                    try {
                        service.transfer(from, to, new BigDecimal("37.25"));
                        succeeded.incrementAndGet();
                    } catch (io.minispring.demo.domain.InsufficientFundsException e) {
                        refused.incrementAndGet();
                    }
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        }
        assertEquals(120, succeeded.get() + refused.get());
        assertEquals(0, totalBefore.compareTo(service.totalBalance()), "the sum of all balances is invariant");
        for (long id = 1; id <= 3; id++) {
            assertTrue(balance(id).signum() >= 0);
        }
    }

    @Test
    @Order(7)
    void theSystemEndpointReportsTheBeanGraphAndRoutes() throws Exception {
        String body = call("GET", "/api/system", null).body();
        assertTrue(body.contains("\"name\":\"transferService\""), "the service is in the bean graph");
        assertTrue(body.contains("POST /api/transfers -> TransferController.transfer()"));
        assertTrue(body.contains("\"startupMillis\""));
    }

    @Test
    @Order(8)
    void theUiIsServedFromTheClassPath() throws Exception {
        var index = call("GET", "/", null);
        assertEquals(200, index.statusCode());
        assertTrue(index.body().contains("<title>"));
        assertEquals(200, call("GET", "/app.js", null).statusCode());
        assertEquals(200, call("GET", "/styles.css", null).statusCode());
    }
}

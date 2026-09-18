package dev.barebones.server;

import com.sun.net.httpserver.HttpServer;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class TrafficControlHandlerTest {
    private TrafficControlHandlerTest() {
    }

    public static void main(String[] args) throws Exception {
        returnsRetryable429WhenClientExhaustsTokens();
        System.out.println("Traffic control handler tests passed");
    }

    private static void returnsRetryable429WhenClientExhaustsTokens() throws Exception {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 1, 10, new AtomicLong()::get);
        TrafficController controller = new TrafficController(10, 10, limiter);
        CopyOnWriteArrayList<ServerEvent> events = new CopyOnWriteArrayList<>();
        ServerMetrics metrics = new ServerMetrics();
        HttpRouter router = new HttpRouter()
                .register("GET", "/test", exchange -> HttpResponses.send(exchange, 200, "ok\n"));
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", new HttpAccessLogger(
                new TrafficControlHandler(router, controller), events::add, metrics));
        server.start();

        try {
            HttpClient client = HttpClient.newHttpClient();
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/test");
            HttpResponse<String> accepted = client.send(
                    HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> rejected = client.send(
                    HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());

            require(accepted.statusCode() == 200, "first request was not accepted");
            require(rejected.statusCode() == 429, "limited request status was " + rejected.statusCode());
            require(rejected.headers().firstValue("Retry-After").orElse("").equals("1"),
                    "Retry-After header was unexpected");
            require(rejected.body().equals("Too Many Requests\n"), "limited response body was unexpected");
            awaitEvents(events, 2);
            require(events.get(1).fields().get("admission").equals("rate_limited"),
                    "rate-limit admission outcome was not logged");
            require(events.get(1).fields().get("status").equals("429"),
                    "rate-limit status was not logged");
            require(metrics.scrape().lines().anyMatch(
                            "barebones_https_responses_total{status=\"429\"} 1"::equals),
                    "rate-limit status was not recorded in metrics");
        } finally {
            server.stop(0);
        }
    }

    private static void awaitEvents(List<ServerEvent> events, int expectedCount) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (events.size() < expectedCount && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        require(events.size() >= expectedCount, "timed out waiting for access events");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}

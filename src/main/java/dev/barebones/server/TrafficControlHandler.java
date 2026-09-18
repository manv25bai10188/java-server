package dev.barebones.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.Objects;

public final class TrafficControlHandler implements HttpHandler {
    private final HttpHandler next;
    private final TrafficController trafficController;

    public TrafficControlHandler(HttpHandler next, TrafficController trafficController) {
        this.next = Objects.requireNonNull(next, "Next HTTP handler must not be null");
        this.trafficController = Objects.requireNonNull(trafficController, "Traffic controller must not be null");
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        TrafficController.Admission admission = trafficController.admitHttps(exchange.getRemoteAddress().getAddress());
        if (!admission.allowed()) {
            HttpExchangeTelemetry.recordAdmission(exchange, admission.rejectionReason().outcome());
            exchange.getResponseHeaders().set("Retry-After", Long.toString(admission.retryAfterSeconds()));
            HttpResponses.send(exchange, 429, "Too Many Requests\n");
            return;
        }

        try {
            next.handle(exchange);
        } finally {
            admission.permit().close();
        }
    }
}

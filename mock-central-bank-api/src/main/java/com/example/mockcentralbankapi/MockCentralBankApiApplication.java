package com.example.mockcentralbankapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

@SpringBootApplication
public class MockCentralBankApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(MockCentralBankApiApplication.class, args);
    }

    @Bean
    RouterFunction<ServerResponse> routes() {
        return route(GET("/api/credit-check/{customerId}"), request -> {
            String customerId = request.pathVariable("customerId");
            Random random = ThreadLocalRandom.current();

            // Simulate latency
            long delayMillis = random.nextInt(4000) + 1000; // 1-5 seconds

            // Simulate failure rate (10%)
            if (random.nextDouble() < 0.1) {
                return Mono.delay(Duration.ofMillis(delayMillis))
                        .then(ServerResponse.status(500).bodyValue("Central Bank API Error for customer: " + customerId));
            }

            // Mock response data
            CreditCheckResponse response = new CreditCheckResponse(
                    random.nextInt(500) + 300, // Credit score 300-800
                    random.nextInt(5),
                    "Mock Payment History for " + customerId
            );

            return Mono.delay(Duration.ofMillis(delayMillis))
                    .then(ServerResponse.ok().bodyValue(response));
        });
    }

    // Simple DTO for the response
    private record CreditCheckResponse(int creditScore, int outstandingLoans, String paymentHistory) {}
}

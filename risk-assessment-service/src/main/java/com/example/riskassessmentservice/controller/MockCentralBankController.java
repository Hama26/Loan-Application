package com.example.riskassessmentservice.controller;

import com.example.riskassessmentservice.client.CentralBankApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Random;
import java.util.UUID;

@RestController
@RequestMapping("/mock/central-bank")
public class MockCentralBankController {

    private static final Logger log = LoggerFactory.getLogger(MockCentralBankController.class);
    private final Random random = new Random();

    // Define the DTO directly here for clarity, or ensure it's accessible
    // If CentralBankApiClient.CentralBankCreditResponse is public and static, it can be used.
    // Assuming it is, as it's a public record inside a public class.

    @GetMapping("/credit-report/{customerId}")
    public Mono<ResponseEntity<CentralBankApiClient.CentralBankCreditResponse>> getMockCreditReport(@PathVariable String customerId) {
        log.info("Mock Central Bank API: Received request for customerId: {}", customerId);

        // Simulate random delay (e.g., 50ms to 500ms)
        long delayMillis = 50 + random.nextInt(451);

        return Mono.delay(Duration.ofMillis(delayMillis))
                .flatMap(aLong -> {
                    // Simulate 10% failure rate
                    if (random.nextDouble() < 0.1) {
                        log.warn("Mock Central Bank API: Simulating failure for customerId: {}", customerId);
                        // Simulate different types of server errors
                        HttpStatus errorStatus = random.nextBoolean() ? HttpStatus.INTERNAL_SERVER_ERROR : HttpStatus.SERVICE_UNAVAILABLE;
                        return Mono.just(ResponseEntity.status(errorStatus).<CentralBankApiClient.CentralBankCreditResponse>build());
                    }

                    // Simulate successful response
                    int creditScore = 600 + random.nextInt(251); // Score between 600 and 850
                    CentralBankApiClient.CentralBankCreditResponse response = new CentralBankApiClient.CentralBankCreditResponse(
                            customerId,
                            creditScore,
                            "ACTIVE",
                            "Credit report looks good. No outstanding issues."
                    );
                    log.info("Mock Central Bank API: Returning success for customerId: {}, Score: {}", customerId, creditScore);
                    return Mono.just(ResponseEntity.ok(response));
                });
    }
}

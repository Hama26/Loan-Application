package com.example.riskassessmentservice.controller;

import com.example.riskassessmentservice.dto.HealthStatus;
import com.example.riskassessmentservice.dto.InitialScoringCompleteEvent;
import com.example.riskassessmentservice.model.RiskAssessment;
import com.example.riskassessmentservice.repository.RiskAssessmentRepository;
import com.example.riskassessmentservice.service.RiskAssessmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import java.math.BigDecimal;
import java.util.UUID;

@RestController
@RequestMapping("/api/risk")
public class RiskAssessmentController {

    private static final Logger log = LoggerFactory.getLogger(RiskAssessmentController.class);

    private final RiskAssessmentService riskAssessmentService;
    private final RiskAssessmentRepository riskAssessmentRepository; // For direct lookups
    private final MeterRegistry meterRegistry;

    private final Counter getAssessmentSuccessCounter;
    private final Counter getAssessmentNotFoundCounter;
    private final Counter reassessSuccessCounter;
    private final Counter reassessErrorCounter;

    public RiskAssessmentController(RiskAssessmentService riskAssessmentService, 
                                  RiskAssessmentRepository riskAssessmentRepository, 
                                  MeterRegistry meterRegistry) {
        this.riskAssessmentService = riskAssessmentService;
        this.riskAssessmentRepository = riskAssessmentRepository;
        this.meterRegistry = meterRegistry;

        this.getAssessmentSuccessCounter = Counter.builder("risk_assessment.controller.requests")
            .tag("method", "getRiskAssessment")
            .tag("status", "success")
            .description("Number of successful risk assessment retrievals")
            .register(meterRegistry);

        this.getAssessmentNotFoundCounter = Counter.builder("risk_assessment.controller.requests")
            .tag("method", "getRiskAssessment")
            .tag("status", "not_found")
            .description("Number of risk assessment retrievals where assessment was not found")
            .register(meterRegistry);

        this.reassessSuccessCounter = Counter.builder("risk_assessment.controller.requests")
            .tag("method", "reassessRisk")
            .tag("status", "success")
            .description("Number of successful reassessments initiated")
            .register(meterRegistry);

        this.reassessErrorCounter = Counter.builder("risk_assessment.controller.requests")
            .tag("method", "reassessRisk")
            .tag("status", "error")
            .description("Number of errors during reassessment initiation")
            .register(meterRegistry);
    }

    @GetMapping("/assessments/{applicationId}")
    public Mono<ResponseEntity<RiskAssessment>> getRiskAssessment(@PathVariable UUID applicationId) {
        log.info("Received request to get risk assessment for applicationId: {}", applicationId);
        // JPA calls are blocking, so schedule on boundedElastic
        // Note: A more reactive approach would involve R2DBC or a query that returns Mono/Flux directly.
        // For simplicity with current setup, we find all and filter, which is not ideal for performance.
        // A dedicated findByApplicationId method in the repository would be better.
        return Mono.fromCallable(() -> riskAssessmentRepository.findAll().stream()
                        .filter(ra -> ra.getApplicationId().equals(applicationId))
                        .findFirst())
                .subscribeOn(Schedulers.boundedElastic())
                .map(assessmentOpt -> assessmentOpt.map(assessment -> {
                            getAssessmentSuccessCounter.increment();
                            return ResponseEntity.ok(assessment);
                        })
                        .orElseGet(() -> {
                            getAssessmentNotFoundCounter.increment();
                            return ResponseEntity.notFound().build();
                        }))
                .defaultIfEmpty(ResponseEntity.notFound().build());
                // Note: defaultIfEmpty for the overall Mono might also mean 'not found' if the callable returns empty & map doesn't run.
                // Consider if getAssessmentNotFoundCounter should also be incremented there if that's a distinct 'not found' case.
                // For now, it's covered if assessmentOpt is empty.
    }

    @PostMapping("/reassess/{applicationId}")
    public Mono<ResponseEntity<RiskAssessment>> reassessRisk(@PathVariable UUID applicationId) {
        log.info("Received request to reassess risk for applicationId: {}", applicationId);
        // For reassessment, we might need to fetch fresh application data.
        // Here, we'll simulate this by creating a new InitialScoringCompleteEvent with the applicationId.
        // In a real system, you might fetch data from another service or database.

        // Mocking event data for reassessment
        // TODO: Replace with actual data fetching logic if needed
        InitialScoringCompleteEvent reassessmentEvent = new InitialScoringCompleteEvent(
                applicationId,
                "CUSTOMER_" + applicationId.toString().substring(0, 8), // Mock customerId
                BigDecimal.valueOf(10000), // Mock loan amount
                BigDecimal.valueOf(5000),  // Mock income
                "Reassessment Purpose",    // Mock purpose
                1.0                        // Mock initial score weight
        );

        return riskAssessmentService.assessRisk(reassessmentEvent)
                .map(assessment -> {
                    reassessSuccessCounter.increment();
                    return ResponseEntity.ok(assessment);
                })
                .onErrorResume(e -> {
                    log.error("Error during reassessment for applicationId: {}: {}", applicationId, e.getMessage());
                    reassessErrorCounter.increment();
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
                });
    }

    @GetMapping("/health")
    public Mono<HealthStatus> getHealth() {
        return Mono.just(new HealthStatus("RiskAssessmentService is UP"));
    }
}

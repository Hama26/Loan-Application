package com.example.riskassessmentservice.service;

import com.example.riskassessmentservice.dto.InitialScoringCompleteEvent;
import com.example.riskassessmentservice.model.RiskAssessment;
import com.example.riskassessmentservice.repository.RiskAssessmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.data.redis.core.ReactiveRedisTemplate;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

@Service
public class RiskAssessmentService {

    private static final Logger log = LoggerFactory.getLogger(RiskAssessmentService.class);

    private final RiskAssessmentRepository riskAssessmentRepository;
    private final KafkaEventProducer kafkaEventProducer;
    private final com.example.riskassessmentservice.client.CentralBankApiClient centralBankApiClient;
    private final ReactiveRedisTemplate<String, RiskAssessment> riskAssessmentRedisTemplate;
    private final MeterRegistry meterRegistry;

    private static final String RISK_ASSESSMENT_CACHE_KEY_PREFIX = "risk_assessment:";
    private static final Duration RISK_ASSESSMENT_CACHE_TTL = Duration.ofHours(24); // Cache for 24 hours

    private final Counter riskAssessmentCacheHitsCounter;
    private final Counter riskAssessmentCacheMissesCounter;
    private final Counter riskAssessmentProcessedCounter;

    public RiskAssessmentService(RiskAssessmentRepository riskAssessmentRepository,
                                 KafkaEventProducer kafkaEventProducer,
                                 com.example.riskassessmentservice.client.CentralBankApiClient centralBankApiClient,
                                 ReactiveRedisTemplate<String, RiskAssessment> riskAssessmentRedisTemplate,
                                 MeterRegistry meterRegistry) {
        this.riskAssessmentRepository = riskAssessmentRepository;
        this.kafkaEventProducer = kafkaEventProducer;
        this.centralBankApiClient = centralBankApiClient;
        this.riskAssessmentRedisTemplate = riskAssessmentRedisTemplate;
        this.meterRegistry = meterRegistry;

        this.riskAssessmentCacheHitsCounter = Counter.builder("cache.risk_assessment.requests")
            .tag("status", "hit")
            .description("Number of cache hits for risk assessments")
            .register(meterRegistry);

        this.riskAssessmentCacheMissesCounter = Counter.builder("cache.risk_assessment.requests")
            .tag("status", "miss")
            .description("Number of cache misses for risk assessments")
            .register(meterRegistry);

        this.riskAssessmentProcessedCounter = Counter.builder("risk_assessment.processed")
            .tag("status", "success") // Assuming we only count successful ones for now
            .description("Number of successfully processed risk assessments")
            .register(meterRegistry);
    }

    public Mono<RiskAssessment> assessRisk(InitialScoringCompleteEvent event) {
        long startTime = System.currentTimeMillis();
        log.info("Starting risk assessment for application ID: {}", event.getApplicationId());

        // Create a new RiskAssessment entity
        RiskAssessment assessment = new RiskAssessment();
        assessment.setApplicationId(event.getApplicationId());
        assessment.setAssessmentDate(LocalDateTime.now());

        return Mono.zip(
                        centralBankApiClient.getCreditReport(event.getCustomerId(), event.getApplicationId()).subscribeOn(Schedulers.boundedElastic()),
                        calculateDebtRatio(event.getIncome(), event.getLoanAmount()).subscribeOn(Schedulers.boundedElastic()),
                        analyzeCollateral(event.getLoanAmount(), event.getLoanPurpose()).subscribeOn(Schedulers.boundedElastic()),
                        performFraudCheck(event.getApplicationId(), event.getCustomerId()).subscribeOn(Schedulers.boundedElastic())
                )
                .flatMap(tuple -> {
                    com.example.riskassessmentservice.client.CentralBankApiClient.CentralBankCreditResponse centralBankResponse = tuple.getT1();
                    DebtRatioResult debtRatioResult = tuple.getT2();
                    CollateralAnalysisResult collateralResult = tuple.getT3();
                    FraudCheckResult fraudResult = tuple.getT4();

                    // Populate assessment with results
                    assessment.setCreditScore(centralBankResponse.creditScore());
                    assessment.setDebtRatio(debtRatioResult.debtRatioPercentage());
                    // ... set other fields from collateralResult and fraudResult

                    // Compute final score (simplified)
                    BigDecimal finalScore = computeFinalScore(
                            centralBankResponse, debtRatioResult, collateralResult, fraudResult, event.getInitialScoreWeight()
                    );
                    assessment.setRiskScore(finalScore);

                    // Make a decision (simplified)
                    if (finalScore.compareTo(BigDecimal.valueOf(60)) >= 0) { // Example threshold
                        assessment.setDecision("APPROVED");
                        assessment.setDecisionReason("Risk score above threshold.");
                    } else {
                        assessment.setDecision("REJECTED");
                        assessment.setDecisionReason("Risk score below threshold.");
                    }
                    assessment.setProcessingTimeMs((int) (System.currentTimeMillis() - startTime));
                    log.info("Risk assessment completed for application ID: {}. Decision: {}", event.getApplicationId(), assessment.getDecision());

                    // Save to database (blocking call, run on boundedElastic scheduler)
                    return Mono.fromCallable(() -> riskAssessmentRepository.save(assessment))
                            .subscribeOn(Schedulers.boundedElastic())
                            .doOnSuccess(savedAssessment -> {
                                log.info("Successfully assessed and saved risk for application ID: {}. Decision: {}", savedAssessment.getApplicationId(), savedAssessment.getDecision());
                                // Produce event to Kafka
                                kafkaEventProducer.sendDecisionEvent(savedAssessment);
                                // Cache the newly created/updated assessment
                                String cacheKey = RISK_ASSESSMENT_CACHE_KEY_PREFIX + savedAssessment.getApplicationId();
                                riskAssessmentRedisTemplate.opsForValue().set(cacheKey, savedAssessment, RISK_ASSESSMENT_CACHE_TTL)
                                    .doOnSuccess(aVoid -> {
                                        log.info("Successfully cached risk assessment for application ID: {}", savedAssessment.getApplicationId());
                                        riskAssessmentProcessedCounter.increment(); // Increment after successful save and cache
                                    })
                                    .doOnError(cacheError -> log.error("Failed to cache risk assessment for application ID: {}: {}", savedAssessment.getApplicationId(), cacheError.getMessage()))
                                    .subscribe(); // Subscribe to execute cache operation
                            })
                            .doOnError(e -> log.error("Error saving risk assessment for application ID: {}: {}", event.getApplicationId(), e.getMessage()));
                });
                .timeout(Duration.ofSeconds(45))
                .doOnError(error -> log.error("Error during risk assessment for application ID: {}: {}", event.getApplicationId(), error.getMessage()))
                .onErrorResume(error -> {
                    // Handle error, potentially save a FAILED assessment state
                    assessment.setDecision("ERROR");
                    assessment.setDecisionReason("Processing error: " + error.getMessage());
                    assessment.setProcessingTimeMs((int) (System.currentTimeMillis() - startTime));
                    return Mono.fromCallable(() -> riskAssessmentRepository.save(assessment))
                            .subscribeOn(Schedulers.boundedElastic())
                            .thenReturn(assessment); // Return the assessment with error status
                });
    }

    // Placeholder for Debt Service Ratio Calculation
    private Mono<DebtRatioResult> calculateDebtRatio(BigDecimal monthlyIncome, BigDecimal loanAmount) {
        log.info("Calculating Debt Ratio for income: {} and loan amount: {}", monthlyIncome, loanAmount);
        // Simplified: Assume total monthly debt is 30% of income for this example
        BigDecimal totalMonthlyDebt = monthlyIncome.multiply(BigDecimal.valueOf(0.3));
        BigDecimal debtRatio = BigDecimal.ZERO;
        if (monthlyIncome.compareTo(BigDecimal.ZERO) > 0) {
            debtRatio = totalMonthlyDebt.divide(monthlyIncome, 4, java.math.RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
        }
        return Mono.just(new DebtRatioResult(debtRatio, "Calculated"));
    }

    // Placeholder for Collateral Analysis
    private Mono<CollateralAnalysisResult> analyzeCollateral(BigDecimal loanAmount, String loanPurpose) {
        log.info("Analyzing Collateral for loan amount: {} and purpose: {}", loanAmount, loanPurpose);
        // Simplified mock result
        return Mono.just(new CollateralAnalysisResult(loanAmount.multiply(BigDecimal.valueOf(0.8)), "Property Verified"));
    }

    // Placeholder for Fraud Detection
    private Mono<FraudCheckResult> performFraudCheck(UUID applicationId, String customerId) {
        log.info("Performing Fraud Check for application ID: {} and customer ID: {}", applicationId, customerId);
        // Simplified mock result
        return Mono.just(new FraudCheckResult(BigDecimal.valueOf(0.05), "No obvious fraud detected")); // 5% fraud risk
    }

    private BigDecimal computeFinalScore(com.example.riskassessmentservice.client.CentralBankApiClient.CentralBankCreditResponse cbr, DebtRatioResult drr, CollateralAnalysisResult car, FraudCheckResult fcr, Double initialWeight) {
        // Simplified scoring - weights from requirements
        // Central Bank Score (normalized, assuming 300-850 range -> 0-100)
        // If API status is not OK (e.g. API_UNAVAILABLE), use a low score like 0 or a penalty.
        double cbScoreValue = cbr.creditScore();
        if (!"OK".equalsIgnoreCase(cbr.status()) && cbr.creditScore() <=0) { // Assuming mock API returns "OK" on success
             // Or use a specific low score if API was unavailable, e.g. 300 or lower
            cbScoreValue = 300; // Default to lowest score if API failed or indicated an issue
        }
        double cbScoreNormalized = Math.max(0, Math.min(100, (cbScoreValue - 300.0) / (850.0 - 300.0) * 100.0));
        BigDecimal centralBankScoreComponent = BigDecimal.valueOf(cbScoreNormalized * 0.35);

        // Debt Ratio Score (lower is better, e.g., <30 Low, 30-50 Med, >50 High -> map to score)
        double debtRatioScoreValue;
        if (drr.debtRatioPercentage().doubleValue() < 30) debtRatioScoreValue = 100;
        else if (drr.debtRatioPercentage().doubleValue() <= 50) debtRatioScoreValue = 60;
        else debtRatioScoreValue = 20;
        BigDecimal debtRatioScoreComponent = BigDecimal.valueOf(debtRatioScoreValue * 0.30);

        // Collateral Score (mocked, assume higher LTV is better or some other metric)
        // For simplicity, let's use a fixed score based on verification
        BigDecimal collateralScoreComponent = BigDecimal.valueOf(car.verifiedValue().doubleValue() > 0 ? 80 * 0.20 : 30 * 0.20);

        // Fraud Check Score (lower risk is better)
        BigDecimal fraudScoreComponent = BigDecimal.valueOf((1 - fcr.fraudRiskScore().doubleValue()) * 100 * 0.15);

        BigDecimal rawFinalScore = centralBankScoreComponent.add(debtRatioScoreComponent).add(collateralScoreComponent).add(fraudScoreComponent);
        return rawFinalScore.multiply(BigDecimal.valueOf(initialWeight != null ? initialWeight : 1.0)); // Apply initial score weight
    }

    public Mono<RiskAssessment> getRiskAssessmentByApplicationId(UUID applicationId) {
        String cacheKey = RISK_ASSESSMENT_CACHE_KEY_PREFIX + applicationId.toString();
        log.info("Attempting to fetch risk assessment for application ID: {} from cache/DB", applicationId);

        return riskAssessmentRedisTemplate.opsForValue().get(cacheKey)
            .doOnSuccess(assessment -> {
                if (assessment != null) {
                    log.info("Cache hit for risk assessment, application ID: {}", applicationId);
                    riskAssessmentCacheHitsCounter.increment();
                } else {
                     // This case implies the key existed but held null, or was an empty Mono.
                    log.info("Cache returned empty for risk assessment, application ID: {}. Treating as miss.", applicationId);
                    riskAssessmentCacheMissesCounter.increment();
                }
            })
            .switchIfEmpty(Mono.defer(() -> {
                log.info("Cache miss for risk assessment, application ID: {}. Fetching from database.", applicationId);
                riskAssessmentCacheMissesCounter.increment();
                // Assuming RiskAssessmentRepository has findByApplicationId method
                return Mono.fromCallable(() -> riskAssessmentRepository.findByApplicationId(applicationId))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(optionalAssessment -> optionalAssessment.map(Mono::just).orElseGet(() -> {
                        log.warn("No risk assessment found in DB for application ID: {}", applicationId);
                        return Mono.empty(); // Or Mono.error if it's an exceptional case not to find one
                    }))
                    .doOnSuccess(assessmentFromDb -> {
                        if (assessmentFromDb != null) {
                            riskAssessmentRedisTemplate.opsForValue().set(cacheKey, assessmentFromDb, RISK_ASSESSMENT_CACHE_TTL)
                                .doOnSuccess(aVoid -> log.info("Successfully cached risk assessment from DB for application ID: {}", applicationId))
                                .doOnError(cacheError -> log.error("Failed to cache risk assessment from DB for application ID: {}: {}", applicationId, cacheError.getMessage()))
                                .subscribe();
                        }
                    });
            }));
    }

    // Placeholder for Reassessment - would also need cache update/invalidation logic
    public Mono<RiskAssessment> reassessRisk(UUID applicationId) {
        log.warn("Reassessment for application ID {} not fully implemented. Placeholder.", applicationId);
        // 1. Fetch existing assessment (potentially from cache/DB using getRiskAssessmentByApplicationId)
        // 2. Perform reassessment logic (e.g., re-evaluate certain factors, re-run external calls if necessary)
        // 3. Update the RiskAssessment object
        // 4. Save the updated assessment to the database
        // 5. Update the cache with the new assessment (overwrite existing entry for this applicationId)
        // 6. Produce a decision event to Kafka
        // For now, just return error to indicate it's a placeholder
        return Mono.error(new UnsupportedOperationException("Reassessment for application ID " + applicationId + " not implemented yet."));
    }

    // Inner DTOs for task results (can be moved to separate files if they grow)
    // CentralBankResponse is now CentralBankApiClient.CentralBankCreditResponse
    private record DebtRatioResult(BigDecimal debtRatioPercentage, String details) {}
    private record CollateralAnalysisResult(BigDecimal verifiedValue, String details) {}
    private record FraudCheckResult(BigDecimal fraudRiskScore, String details) {}
}

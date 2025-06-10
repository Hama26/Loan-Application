package com.example.riskassessmentservice.service;

import com.example.riskassessmentservice.client.CentralBankApiClient;
import com.example.riskassessmentservice.model.RiskAssessment;
import com.example.riskassessmentservice.model.RiskAssessmentRequest;
import com.example.riskassessmentservice.model.RiskDecision;
import com.example.riskassessmentservice.model.RiskStatus;
import com.example.riskassessmentservice.repository.RiskAssessmentRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RiskAssessmentServiceTest {

    @Mock
    private RiskAssessmentRepository riskAssessmentRepository;

    @Mock
    private CentralBankApiClient centralBankApiClient;

    @Mock
    private KafkaEventProducer kafkaEventProducer;

    @Mock
    private ReactiveRedisTemplate<String, RiskAssessment> riskAssessmentRedisTemplate;

    @Mock
    private ReactiveValueOperations<String, RiskAssessment> reactiveValueOpsRiskAssessment;
    
    private MeterRegistry meterRegistry;

    @InjectMocks
    private RiskAssessmentService riskAssessmentService;

    private final String applicationId = UUID.randomUUID().toString();
    private final String customerId = "cust123";
    private final double loanAmount = 10000.0;
    private final int termMonths = 12;

    @BeforeEach
    void setUp() {
        // Initialize MeterRegistry and mock its counter creation
        meterRegistry = new SimpleMeterRegistry();
        riskAssessmentService.setMeterRegistry(meterRegistry); // Assuming a setter or constructor injection for MeterRegistry
        
        // Mock ReactiveValueOperations
        when(riskAssessmentRedisTemplate.opsForValue()).thenReturn(reactiveValueOpsRiskAssessment);
    }

    @Test
    void assessRisk_whenNewAssessment_performsAllStepsAndCaches() {
        RiskAssessmentRequest request = new RiskAssessmentRequest(applicationId, customerId, loanAmount, termMonths, null);
        CentralBankApiClient.CentralBankCreditResponse creditResponse = new CentralBankApiClient.CentralBankCreditResponse(customerId, 750, "ACTIVE", "Good credit history");
        RiskAssessment expectedAssessment = new RiskAssessment();
        expectedAssessment.setApplicationId(UUID.fromString(applicationId));
        expectedAssessment.setCustomerId(customerId);
        expectedAssessment.setScore(75); // Example score
        expectedAssessment.setStatus(RiskStatus.APPROVED);
        expectedAssessment.setAssessedAt(LocalDateTime.now());
        expectedAssessment.setDecision(RiskDecision.APPROVE);
        expectedAssessment.setReason("Risk score: 75. Credit score: 750.");

        // Mock CentralBankApiClient
        when(centralBankApiClient.getCreditReport(customerId)).thenReturn(Mono.just(creditResponse));

        // Mock Repository save
        when(riskAssessmentRepository.save(any(RiskAssessment.class))).thenReturn(Mono.just(expectedAssessment));

        // Mock Kafka producer
        when(kafkaEventProducer.sendDecisionEvent(any(RiskAssessment.class))).thenReturn(Mono.empty());

        // Mock Redis cache SET operation
        String cacheKey = "risk_assessment::" + applicationId;
        when(reactiveValueOpsRiskAssessment.set(eq(cacheKey), any(RiskAssessment.class), any(Duration.class))).thenReturn(Mono.just(true));

        StepVerifier.create(riskAssessmentService.assessRisk(request))
                .expectNextMatches(assessment -> {
                    return assessment.getApplicationId().toString().equals(applicationId) &&
                           assessment.getStatus() == RiskStatus.APPROVED;
                })
                .verifyComplete();

        verify(centralBankApiClient).getCreditReport(customerId);
        verify(riskAssessmentRepository).save(any(RiskAssessment.class));
        verify(kafkaEventProducer).sendDecisionEvent(any(RiskAssessment.class));
        verify(reactiveValueOpsRiskAssessment).set(eq(cacheKey), any(RiskAssessment.class), any(Duration.class));
    }

    @Test
    void getRiskAssessmentByApplicationId_whenCacheHit() {
        RiskAssessment cachedAssessment = new RiskAssessment();
        cachedAssessment.setApplicationId(UUID.fromString(applicationId));
        cachedAssessment.setStatus(RiskStatus.PENDING_REVIEW);

        String cacheKey = "risk_assessment::" + applicationId;
        when(reactiveValueOpsRiskAssessment.get(cacheKey)).thenReturn(Mono.just(cachedAssessment));

        StepVerifier.create(riskAssessmentService.getRiskAssessmentByApplicationId(applicationId))
                .expectNext(cachedAssessment)
                .verifyComplete();

        verify(reactiveValueOpsRiskAssessment).get(cacheKey);
        // Verify meter increment for cache hit (implementation detail, can be added later if needed)
    }

    @Test
    void getRiskAssessmentByApplicationId_whenCacheMissAndDbHit() {
        RiskAssessment dbAssessment = new RiskAssessment();
        dbAssessment.setApplicationId(UUID.fromString(applicationId));
        dbAssessment.setStatus(RiskStatus.DECLINED);

        String cacheKey = "risk_assessment::" + applicationId;
        when(reactiveValueOpsRiskAssessment.get(cacheKey)).thenReturn(Mono.empty()); // Cache miss
        when(riskAssessmentRepository.findByApplicationId(UUID.fromString(applicationId))).thenReturn(Mono.just(dbAssessment));
        when(reactiveValueOpsRiskAssessment.set(eq(cacheKey), any(RiskAssessment.class), any(Duration.class))).thenReturn(Mono.just(true)); // Cache set after DB hit

        StepVerifier.create(riskAssessmentService.getRiskAssessmentByApplicationId(applicationId))
                .expectNext(dbAssessment)
                .verifyComplete();

        verify(reactiveValueOpsRiskAssessment).get(cacheKey);
        verify(riskAssessmentRepository).findByApplicationId(UUID.fromString(applicationId));
        verify(reactiveValueOpsRiskAssessment).set(eq(cacheKey), any(RiskAssessment.class), any(Duration.class));
        // Verify meter increments (implementation detail)
    }

    @Test
    void getRiskAssessmentByApplicationId_whenCacheMissAndDbMiss() {
        String cacheKey = "risk_assessment::" + applicationId;
        when(reactiveValueOpsRiskAssessment.get(cacheKey)).thenReturn(Mono.empty()); // Cache miss
        when(riskAssessmentRepository.findByApplicationId(UUID.fromString(applicationId))).thenReturn(Mono.empty()); // DB miss

        StepVerifier.create(riskAssessmentService.getRiskAssessmentByApplicationId(applicationId))
                .expectComplete() // Expecting empty Mono for not found
                .verify();

        verify(reactiveValueOpsRiskAssessment).get(cacheKey);
        verify(riskAssessmentRepository).findByApplicationId(UUID.fromString(applicationId));
        // Verify meter increments (implementation detail)
    }

    // TODO: Add tests for reassessRisk (currently placeholder)
    // TODO: Add tests for error handling, e.g., CentralBankApiClient failure
    // TODO: Add tests for metrics (cache hit/miss counters, processed counter)
}

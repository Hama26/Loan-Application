package com.example.riskassessmentservice.service;

import com.example.riskassessmentservice.dto.InitialScoringCompleteEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

@Service
public class KafkaEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventConsumer.class);
    private final RiskAssessmentService riskAssessmentService;
    private final MeterRegistry meterRegistry;

    private final Counter scoringEventsConsumedSuccessCounter;
    private final Counter scoringEventsConsumedErrorCounter;

    public KafkaEventConsumer(RiskAssessmentService riskAssessmentService, MeterRegistry meterRegistry) {
        this.riskAssessmentService = riskAssessmentService;
        this.meterRegistry = meterRegistry;

        this.scoringEventsConsumedSuccessCounter = Counter.builder("kafka.consumer.messages")
            .tag("topic", "scoring-events")
            .tag("status", "success")
            .description("Number of successfully consumed messages from scoring-events topic")
            .register(meterRegistry);

        this.scoringEventsConsumedErrorCounter = Counter.builder("kafka.consumer.messages")
            .tag("topic", "scoring-events")
            .tag("status", "error")
            .description("Number of errors consuming messages from scoring-events topic")
            .register(meterRegistry);
    }

    @KafkaListener(topics = "scoring-events", // As per user requirements, actual topic name from application.yml if configured
                   containerFactory = "kafkaListenerContainerFactory")
    public void consumeInitialScoringCompleteEvent(@Payload InitialScoringCompleteEvent event) {
        log.info("Received InitialScoringCompleteEvent: {}", event);
        riskAssessmentService.assessRisk(event)
                .doOnSuccess(assessment -> {
                    log.info("Successfully processed risk assessment for application: {}", event.getApplicationId());
                    scoringEventsConsumedSuccessCounter.increment();
                })
                .doOnError(error -> {
                    log.error("Failed to process risk assessment for application: {}: {}", event.getApplicationId(), error.getMessage());
                    scoringEventsConsumedErrorCounter.increment();
                })
                .subscribe(); // Subscribe to trigger the reactive flow
    }
}

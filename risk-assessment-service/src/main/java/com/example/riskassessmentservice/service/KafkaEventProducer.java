package com.example.riskassessmentservice.service;

import com.example.riskassessmentservice.dto.DecisionEvent;
import com.example.riskassessmentservice.model.RiskAssessment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

@Service
public class KafkaEventProducer {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventProducer.class);
    private static final String DECISION_TOPIC = "decision-events"; // As per user requirements

    private final KafkaTemplate<String, DecisionEvent> kafkaTemplate;
    private final MeterRegistry meterRegistry;

    private final Counter decisionEventsProducedSuccessCounter;
    private final Counter decisionEventsProducedErrorCounter;

    public KafkaEventProducer(KafkaTemplate<String, DecisionEvent> kafkaTemplate, MeterRegistry meterRegistry) {
        this.kafkaTemplate = kafkaTemplate;
        this.meterRegistry = meterRegistry;

        this.decisionEventsProducedSuccessCounter = Counter.builder("kafka.producer.messages")
            .tag("topic", DECISION_TOPIC)
            .tag("status", "success")
            .description("Number of successfully produced messages to decision-events topic")
            .register(meterRegistry);

        this.decisionEventsProducedErrorCounter = Counter.builder("kafka.producer.messages")
            .tag("topic", DECISION_TOPIC)
            .tag("status", "error")
            .description("Number of errors producing messages to decision-events topic")
            .register(meterRegistry);
    }

    public void sendDecisionEvent(RiskAssessment assessment) {
        DecisionEvent event = new DecisionEvent(
                assessment.getApplicationId(),
                assessment.getId(),
                assessment.getDecision(),
                assessment.getDecisionReason(),
                assessment.getRiskScore()
        );
        try {
            kafkaTemplate.send(DECISION_TOPIC, assessment.getApplicationId().toString(), event)
                    .whenComplete((result, ex) -> {
                        if (ex == null) {
                            log.info("Sent DecisionEvent for applicationId {}: offset = {}, partition = {}",
                                    event.getApplicationId(), result.getRecordMetadata().offset(), result.getRecordMetadata().partition());
                            decisionEventsProducedSuccessCounter.increment();
                        } else {
                            log.error("Failed to send DecisionEvent for applicationId {}: {}", event.getApplicationId(), ex.getMessage());
                            decisionEventsProducedErrorCounter.increment();
                        }
                    });
        } catch (Exception e) {
            log.error("Exception while sending DecisionEvent for applicationId {}: {}", event.getApplicationId(), e.getMessage(), e);
            decisionEventsProducedErrorCounter.increment(); // Also count exceptions during send attempt as errors
        }
    }
}

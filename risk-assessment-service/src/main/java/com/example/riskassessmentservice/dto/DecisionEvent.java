package com.example.riskassessmentservice.dto;

import java.math.BigDecimal;
import java.util.UUID;

// This DTO represents the event produced to the 'decision-events' topic.
public class DecisionEvent {

    private UUID applicationId;
    private UUID assessmentId;
    private String decision;
    private String reason;
    private BigDecimal finalRiskScore;

    // Constructors
    public DecisionEvent() {
    }

    public DecisionEvent(UUID applicationId, UUID assessmentId, String decision, String reason, BigDecimal finalRiskScore) {
        this.applicationId = applicationId;
        this.assessmentId = assessmentId;
        this.decision = decision;
        this.reason = reason;
        this.finalRiskScore = finalRiskScore;
    }

    // Getters and Setters
    public UUID getApplicationId() {
        return applicationId;
    }

    public void setApplicationId(UUID applicationId) {
        this.applicationId = applicationId;
    }

    public UUID getAssessmentId() {
        return assessmentId;
    }

    public void setAssessmentId(UUID assessmentId) {
        this.assessmentId = assessmentId;
    }

    public String getDecision() {
        return decision;
    }

    public void setDecision(String decision) {
        this.decision = decision;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public BigDecimal getFinalRiskScore() {
        return finalRiskScore;
    }

    public void setFinalRiskScore(BigDecimal finalRiskScore) {
        this.finalRiskScore = finalRiskScore;
    }

    @Override
    public String toString() {
        return "DecisionEvent{" +
                "applicationId=" + applicationId +
                ", assessmentId=" + assessmentId +
                ", decision='" + decision + '\'' +
                ", reason='" + reason + '\'' +
                ", finalRiskScore=" + finalRiskScore +
                '}';
    }
}

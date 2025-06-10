package com.example.riskassessmentservice.dto;

import java.math.BigDecimal;
import java.util.UUID;

// This DTO represents the event consumed from the 'scoring-events' topic.
// Its structure should match the event produced by the upstream service.
// For now, we'll assume a basic structure. Adjust fields as necessary based on actual event data.
public class InitialScoringCompleteEvent {

    private UUID applicationId;
    private String customerId; // Assuming customerId is needed for Central Bank check
    private BigDecimal loanAmount;
    private BigDecimal income;
    private String loanPurpose;
    private Double initialScoreWeight; // Example: a weight derived from initial scoring

    // Constructors
    public InitialScoringCompleteEvent() {
    }

    public InitialScoringCompleteEvent(UUID applicationId, String customerId, BigDecimal loanAmount, BigDecimal income, String loanPurpose, Double initialScoreWeight) {
        this.applicationId = applicationId;
        this.customerId = customerId;
        this.loanAmount = loanAmount;
        this.income = income;
        this.loanPurpose = loanPurpose;
        this.initialScoreWeight = initialScoreWeight;
    }

    // Getters and Setters
    public UUID getApplicationId() {
        return applicationId;
    }

    public void setApplicationId(UUID applicationId) {
        this.applicationId = applicationId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }

    public BigDecimal getLoanAmount() {
        return loanAmount;
    }

    public void setLoanAmount(BigDecimal loanAmount) {
        this.loanAmount = loanAmount;
    }

    public BigDecimal getIncome() {
        return income;
    }

    public void setIncome(BigDecimal income) {
        this.income = income;
    }

    public String getLoanPurpose() {
        return loanPurpose;
    }

    public void setLoanPurpose(String loanPurpose) {
        this.loanPurpose = loanPurpose;
    }

    public Double getInitialScoreWeight() {
        return initialScoreWeight;
    }

    public void setInitialScoreWeight(Double initialScoreWeight) {
        this.initialScoreWeight = initialScoreWeight;
    }

    @Override
    public String toString() {
        return "InitialScoringCompleteEvent{" +
                "applicationId=" + applicationId +
                ", customerId='" + customerId + '\'' +
                ", loanAmount=" + loanAmount +
                ", income=" + income +
                ", loanPurpose='" + loanPurpose + '\'' +
                ", initialScoreWeight=" + initialScoreWeight +
                '}';
    }
}

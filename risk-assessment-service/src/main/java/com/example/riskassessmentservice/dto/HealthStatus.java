package com.example.riskassessmentservice.dto;

public class HealthStatus {
    private String status;

    public HealthStatus(String status) {
        this.status = status;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}

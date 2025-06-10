package com.example.riskassessmentservice.repository;

import com.example.riskassessmentservice.model.RiskFactor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface RiskFactorRepository extends JpaRepository<RiskFactor, UUID> {
    // Custom query methods can be added here if needed.
}

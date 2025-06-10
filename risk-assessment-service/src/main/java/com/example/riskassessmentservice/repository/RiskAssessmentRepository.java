package com.example.riskassessmentservice.repository;

import com.example.riskassessmentservice.model.RiskAssessment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.util.Optional; // Added import
import java.util.UUID;

@Repository
public interface RiskAssessmentRepository extends JpaRepository<RiskAssessment, UUID> {
    // Note: JpaRepository is inherently blocking. For a fully reactive approach with PostgreSQL,
    // you would typically use R2DBC (e.g., spring-boot-starter-data-r2dbc).
    // However, since the user specified JPA and WebFlux, we'll proceed with JPA and acknowledge
    // that database calls will be blocking and should be scheduled on a bounded elastic scheduler
    // when called from reactive streams to avoid blocking Netty event loop threads.

    // Custom query methods can be added here if needed, for example:
    // Mono<RiskAssessment> findByApplicationId(UUID applicationId);
    // This would require R2DBC or careful handling with Project Reactor's publishOn().

    Optional<RiskAssessment> findByApplicationId(UUID applicationId);
}

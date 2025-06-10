package com.example.riskassessmentservice.client;

import com.example.riskassessmentservice.model.ExternalApiCall;
import com.example.riskassessmentservice.model.CentralBankCreditResponse;
import com.example.riskassessmentservice.repository.ExternalApiCallRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import org.springframework.data.redis.core.ReactiveRedisTemplate;

@Component
public class CentralBankApiClient {

    private static final Logger log = LoggerFactory.getLogger(CentralBankApiClient.class);
    private final WebClient centralBankWebClient;
    private final ExternalApiCallRepository externalApiCallRepository;
    private final ReactiveRedisTemplate<String, CentralBankCreditResponse> redisTemplate;
    private final MeterRegistry meterRegistry;

    private static final String CENTRAL_BANK_CACHE_KEY_PREFIX = "central_bank_response:";
    private static final Duration CENTRAL_BANK_CACHE_TTL = Duration.ofHours(1);

    private final Counter cacheHitsCounter;
    private final Counter cacheMissesCounter;
    private final Counter apiFallbackCounter;

    // This DTO should match the response from mock-central-bank-api
    public record CentralBankCreditResponse(String customerId, int creditScore, String status, String details) {}

    public CentralBankApiClient(@Qualifier("centralBankWebClient") WebClient centralBankWebClient,
                                ReactiveRedisTemplate<String, CentralBankCreditResponse> redisTemplate,
                                ExternalApiCallRepository externalApiCallRepository,
                                MeterRegistry meterRegistry) {
        this.centralBankWebClient = centralBankWebClient;
        this.redisTemplate = redisTemplate;
        this.externalApiCallRepository = externalApiCallRepository;
        this.meterRegistry = meterRegistry;

        this.cacheHitsCounter = Counter.builder("cache.central_bank_api.requests")
            .tag("status", "hit")
            .description("Number of cache hits for Central Bank API responses")
            .register(meterRegistry);

        this.cacheMissesCounter = Counter.builder("cache.central_bank_api.requests")
            .tag("status", "miss")
            .description("Number of cache misses for Central Bank API responses")
            .register(meterRegistry);

        this.apiFallbackCounter = Counter.builder("central_bank_api.calls")
            .tag("outcome", "fallback")
            .description("Number of times Central Bank API call resulted in a fallback")
            .register(meterRegistry);
    }

    @Bulkhead(name = "centralBankApi") // Controls concurrent calls to the entire method (cache + API)
    @CircuitBreaker(name = "centralBankApi", fallbackMethod = "getCreditReportFallback") // Protects the API call part, via switchIfEmpty
    @Retry(name = "centralBankApi") // Retries the API call part, via switchIfEmpty
    public Mono<CentralBankCreditResponse> getCreditReport(String customerId, UUID applicationId) {
        String cacheKey = CENTRAL_BANK_CACHE_KEY_PREFIX + customerId;

        // Try to get from cache first
        return redisTemplate.opsForValue().get(cacheKey)
            .doOnSuccess(response -> {
                if (response != null) {
                    log.info("Cache hit for customerId: {}. Serving from cache.", customerId);
                    cacheHitsCounter.increment();
                } else {
                    // This case should ideally not happen if switchIfEmpty is used correctly,
                    // but as a safeguard or if get() can return Mono<null>.
                    log.info("Cache returned empty Mono for customerId: {}. Treating as miss.", customerId);
                    cacheMissesCounter.increment(); 
                }
            })
            .switchIfEmpty(Mono.defer(() -> {
                log.info("Cache miss for customerId: {}. Fetching from Central Bank API.", customerId);
                cacheMissesCounter.increment();
                return fetchFromApiAndCache(customerId, applicationId, cacheKey);
            }));
    }

    // This method contains the original API call logic, now enhanced with caching
    private Mono<CentralBankCreditResponse> fetchFromApiAndCache(String customerId, UUID applicationId, String cacheKey) {
        log.info("Fetching credit report for customerId: {} from Central Bank API (fetchFromApiAndCache)", customerId);
        LocalDateTime requestTime = LocalDateTime.now();

        return centralBankWebClient.get()
                .uri("/api/credit-check/{customerId}", customerId)
                .retrieve()
                .onStatus(HttpStatus::isError, clientResponse -> {
                    log.error("Error from Central Bank API: Status = {}, Body = {}", 
                              clientResponse.statusCode(), 
                              clientResponse.bodyToMono(String.class).defaultIfEmpty("[empty body]"));
                    return clientResponse.bodyToMono(String.class)
                        .flatMap(body -> Mono.error(new CentralApiException("Central Bank API error: " + clientResponse.statusCode() + " - " + body)));
                })
                .bodyToMono(CentralBankCreditResponse.class)
                .doOnSuccess(response -> {
                    if (response != null && response.creditScore() > 0) { // Corrected: response.creditScore()
                        log.info("Successfully received credit report for customerId: {} from API", customerId);
                        saveApiCallLog(applicationId, "CentralBankAPI_Success", requestTime, LocalDateTime.now(), HttpStatus.OK.value(), false);
                        // Cache the successful response
                        redisTemplate.opsForValue().set(cacheKey, response, CENTRAL_BANK_CACHE_TTL)
                            .doOnSuccess(aVoid -> log.info("Successfully cached API response for customerId: {}", customerId))
                            .doOnError(cacheError -> log.error("Failed to cache API response for customerId: {}: {}", customerId, cacheError.getMessage()))
                            .subscribe(); // Subscribe to execute the cache operation
                    } else {
                        log.warn("Received no/invalid data from Central Bank API for customerId: {}. Response: {}", customerId, response);
                        saveApiCallLog(applicationId, "CentralBankAPI_NoData", requestTime, LocalDateTime.now(), HttpStatus.OK.value(), false); // Or a more specific status if applicable
                    }
                })
                .doOnError(error -> {
                    log.error("Error fetching credit report for customerId: {}: {}", customerId, error.getMessage());
                    // Determine status code for logging
                    int statusCode = HttpStatus.INTERNAL_SERVER_ERROR.value(); // Default
                    if (error instanceof CentralApiException && error.getCause() instanceof org.springframework.web.reactive.function.client.WebClientResponseException webClientEx) {
                        statusCode = webClientEx.getRawStatusCode();
                    } else if (error instanceof org.springframework.web.reactive.function.client.WebClientRequestException) {
                        // For network errors like connection refused, often no HTTP status is available
                        statusCode = HttpStatus.SERVICE_UNAVAILABLE.value(); // Or a custom code
                    }
                    saveApiCallLog(applicationId, "CentralBankAPI_Error", requestTime, LocalDateTime.now(), statusCode, false);
                    // Propagate the error to be handled by Resilience4j's Retry/CircuitBreaker on getCreditReport
                    return Mono.error(error); 
                });
    }

    // Fallback method for getCreditReport (called by Resilience4j CircuitBreaker)
    @SuppressWarnings("unused")
    private Mono<CentralBankCreditResponse> getCreditReportFallback(String customerId, UUID applicationId, Throwable t) {
        log.warn("Circuit breaker fallback for getCreditReport customerId: {}. Error: {}", customerId, t.getMessage());
        apiFallbackCounter.increment();
        // Log the API call attempt with a specific status indicating fallback
        saveApiCallLog(applicationId, "CentralBankAPI_Fallback", LocalDateTime.now(), LocalDateTime.now(), HttpStatus.SERVICE_UNAVAILABLE.value(), false);
        return Mono.just(new CentralBankCreditResponse(customerId, 0, "FALLBACK_API_UNAVAILABLE", "Service temporarily unavailable. Please try again later."));
    }

    private void saveApiCallLog(UUID applicationId, String apiName, LocalDateTime requestTime, LocalDateTime responseTime, int statusCode, boolean cached) {
        ExternalApiCall logEntry = new ExternalApiCall();
        logEntry.setApplicationId(applicationId);
        logEntry.setApiName(apiName);
        logEntry.setRequestTime(requestTime);
        logEntry.setResponseTime(responseTime);
        logEntry.setStatusCode(statusCode);
        logEntry.setCached(cached);
        
        Mono.fromRunnable(() -> externalApiCallRepository.save(logEntry))
            .subscribeOn(Schedulers.boundedElastic())
            .doOnError(e -> log.error("Failed to save external API call log: {}", e.getMessage()))
            .subscribe();
    }

    // Custom exception for Central Bank API issues
    public static class CentralApiException extends RuntimeException {
        public CentralApiException(String message) {
            super(message);
        }
        public CentralApiException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

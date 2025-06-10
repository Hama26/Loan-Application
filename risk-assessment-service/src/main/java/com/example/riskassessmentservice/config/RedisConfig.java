package com.example.riskassessmentservice.config;

import com.example.riskassessmentservice.model.CentralBankCreditResponse;
import com.example.riskassessmentservice.model.RiskAssessment; // Added import
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    @Bean
    public ReactiveRedisTemplate<String, CentralBankCreditResponse> centralBankResponseRedisTemplate(
            ReactiveRedisConnectionFactory factory) {

        StringRedisSerializer keySerializer = new StringRedisSerializer();

        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule()); // For Java 8 Date/Time types
        // Configure ObjectMapper further if needed (e.g., visibility, date formats)

        Jackson2JsonRedisSerializer<CentralBankCreditResponse> valueSerializer =
                new Jackson2JsonRedisSerializer<>(objectMapper, CentralBankCreditResponse.class);

        RedisSerializationContext.RedisSerializationContextBuilder<String, CentralBankCreditResponse> builder =
                RedisSerializationContext.newSerializationContext(keySerializer);

        RedisSerializationContext<String, CentralBankCreditResponse> context =
                builder.value(valueSerializer).hashValue(valueSerializer)
                       .hashKey(keySerializer).build();

        return new ReactiveRedisTemplate<>(factory, context);
    }

    @Bean
    public ReactiveRedisTemplate<String, RiskAssessment> riskAssessmentRedisTemplate(
            ReactiveRedisConnectionFactory factory) {
        StringRedisSerializer keySerializer = new StringRedisSerializer();

        // ObjectMapper setup for RiskAssessment, including JavaTimeModule for date/time types
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        // objectMapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        // objectMapper.activateDefaultTyping(LaissezFaireSubTypeValidator.instance, ObjectMapper.DefaultTyping.NON_FINAL);

        Jackson2JsonRedisSerializer<RiskAssessment> valueSerializer =
                new Jackson2JsonRedisSerializer<>(objectMapper, RiskAssessment.class);

        RedisSerializationContext.RedisSerializationContextBuilder<String, RiskAssessment> builder =
                RedisSerializationContext.newSerializationContext(keySerializer);

        RedisSerializationContext<String, RiskAssessment> context =
                builder.value(valueSerializer).hashValue(valueSerializer)
                       .hashKey(keySerializer).build();

        return new ReactiveRedisTemplate<>(factory, context);
    }
}


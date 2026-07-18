package com.paymentflow.merchant.config;

import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import tools.jackson.databind.ObjectMapper;

/**
 * Cache-aside merchant profile lookups (Redis, TTL via {@code spring.cache.redis.time-to-live}).
 * Boot's default {@code RedisCacheManager} serializes values with JDK serialization,
 * which requires cached types to implement {@code Serializable} and produces an
 * opaque binary blob in Redis; every other payload in this platform is JSON, so cached
 * values are too — using the app's own Jackson 3 {@code ObjectMapper} (D19), not the
 * legacy Jackson-2-only {@code GenericJackson2JsonRedisSerializer}.
 */
@Configuration
public class CacheConfig {

    @Bean
    public RedisCacheManagerBuilderCustomizer jsonValueSerializationCustomizer(ObjectMapper objectMapper) {
        RedisSerializationContext.SerializationPair<Object> jsonPair =
                RedisSerializationContext.SerializationPair.fromSerializer(new GenericJacksonJsonRedisSerializer(objectMapper));

        return builder -> builder.cacheDefaults(RedisCacheConfiguration.defaultCacheConfig()
                .serializeValuesWith(jsonPair));
    }
}

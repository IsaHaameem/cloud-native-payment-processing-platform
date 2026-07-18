package com.paymentflow.merchant.config;

import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import tools.jackson.databind.jsontype.PolymorphicTypeValidator;

/**
 * Cache-aside merchant profile lookups (Redis, TTL via {@code spring.cache.redis.time-to-live}).
 * Boot's default {@code RedisCacheManager} serializes values with JDK serialization,
 * which requires cached types to implement {@code Serializable} and produces an
 * opaque binary blob in Redis; every other payload in this platform is JSON, so cached
 * values are too.
 *
 * <p>This uses {@code GenericJacksonJsonRedisSerializer.builder()} with
 * {@code enableDefaultTyping(...)} — its own dedicated {@code ObjectMapper}, not the
 * app's shared request/response one (D19's {@code ObjectMapper} bean) — because a
 * cache read has no target type to deserialize into ahead of time; the serializer
 * must embed type metadata in the stored JSON to reconstruct the original class
 * ({@code MerchantResponse}) instead of a generic {@code LinkedHashMap}. The
 * validator scopes which types are trusted to this service's own package: Redis here
 * is private, server-authored storage, not a channel for untrusted input, but scoping
 * the validator (rather than "unsafe" blanket default typing) costs nothing and
 * avoids the app's shared mapper needing this — arguably more sensitive — setting
 * enabled globally.
 */
@Configuration
public class CacheConfig {

    @Bean
    public RedisCacheManagerBuilderCustomizer jsonValueSerializationCustomizer() {
        PolymorphicTypeValidator typeValidator = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("com.paymentflow.merchant")
                .build();
        GenericJacksonJsonRedisSerializer serializer = GenericJacksonJsonRedisSerializer.builder()
                .enableDefaultTyping(typeValidator)
                .build();
        RedisSerializationContext.SerializationPair<Object> jsonPair =
                RedisSerializationContext.SerializationPair.fromSerializer(serializer);

        return builder -> builder.cacheDefaults(RedisCacheConfiguration.defaultCacheConfig()
                .serializeValuesWith(jsonPair));
    }
}

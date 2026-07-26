package com.paymentflow.gateway.ratelimit;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

/** Loads the M20.5 token-bucket + quota script (see {@code scripts/api-key-rate-limit.lua}). */
@Configuration
public class RateLimitScriptConfig {

    @Bean
    @SuppressWarnings("rawtypes")
    public RedisScript<List> apiKeyRateLimitScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/api-key-rate-limit.lua"));
        // Redis returns a Lua table as a multi-bulk reply, which Spring Data maps to a List.
        script.setResultType(List.class);
        return script;
    }
}

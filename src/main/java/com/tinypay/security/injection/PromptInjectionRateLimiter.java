package com.tinypay.security.injection;

import com.tinypay.global.exception.CustomException;
import com.tinypay.global.exception.ErrorType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Slf4j
@Component
@RequiredArgsConstructor
public class PromptInjectionRateLimiter {

    private static final String KEY_PREFIX = "security:prompt-injection:user:";

    private final StringRedisTemplate redisTemplate;
    private final PromptInjectionMetrics metrics;

    @Value("${security.prompt-injection.rate-limit.max-attempts:3}")
    private int maxAttempts;

    @Value("${security.prompt-injection.rate-limit.window-minutes:10}")
    private long windowMinutes;

    public void checkAllowed(Long userId) {
        if (userId == null) return;
        try {
            String value = redisTemplate.opsForValue().get(key(userId));
            if (value != null && Long.parseLong(value) >= maxAttempts) {
                metrics.recordRateLimited();
                throw new CustomException(ErrorType.PROMPT_INJECTION_RATE_LIMITED);
            }
        } catch (CustomException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("[PromptInjection] Redis rate-limit 조회 실패, 탐지는 계속 수행: {}", e.getMessage());
        }
    }

    public long recordDetection(Long userId) {
        if (userId == null) return 0;
        try {
            String key = key(userId);
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redisTemplate.expire(key, Duration.ofMinutes(windowMinutes));
            }
            return count == null ? 0 : count;
        } catch (RuntimeException e) {
            log.warn("[PromptInjection] Redis rate-limit 기록 실패: {}", e.getMessage());
            return 0;
        }
    }

    private String key(Long userId) {
        return KEY_PREFIX + userId;
    }
}

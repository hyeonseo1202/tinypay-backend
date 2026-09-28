package com.tinypay.security.injection;

import com.tinypay.global.exception.CustomException;
import com.tinypay.global.exception.ErrorType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PromptInjectionRateLimiterTest {

    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOperations;
    @Mock PromptInjectionMetrics metrics;

    private PromptInjectionRateLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new PromptInjectionRateLimiter(redisTemplate, metrics);
        ReflectionTestUtils.setField(limiter, "maxAttempts", 3);
        ReflectionTestUtils.setField(limiter, "windowMinutes", 10L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void thirdDetectionBlocksFollowingRequests() {
        when(valueOperations.get("security:prompt-injection:user:1")).thenReturn("3");

        assertThatThrownBy(() -> limiter.checkAllowed(1L))
                .isInstanceOf(CustomException.class)
                .satisfies(error -> assertThat(((CustomException) error).getErrorType())
                        .isEqualTo(ErrorType.PROMPT_INJECTION_RATE_LIMITED));

        verify(metrics).recordRateLimited();
    }

    @Test
    void firstDetectionCreatesCounterWithExpiry() {
        when(valueOperations.increment("security:prompt-injection:user:1")).thenReturn(1L);

        assertThat(limiter.recordDetection(1L)).isEqualTo(1L);

        verify(redisTemplate).expire("security:prompt-injection:user:1", Duration.ofMinutes(10));
    }

    @Test
    void redisFailureDoesNotDisablePatternDetectionLayer() {
        when(valueOperations.get("security:prompt-injection:user:1"))
                .thenThrow(new IllegalStateException("redis unavailable"));

        limiter.checkAllowed(1L);
    }
}

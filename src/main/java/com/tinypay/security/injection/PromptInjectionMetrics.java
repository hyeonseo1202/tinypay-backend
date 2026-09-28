package com.tinypay.security.injection;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PromptInjectionMetrics {

    private final MeterRegistry meterRegistry;

    public void recordDetection(DetectionResult detection, String stage) {
        detection.getMatchedRules().forEach(rule -> Counter.builder("tinypay.security.prompt_injection.detected")
                .tag("rule", rule.name())
                .tag("severity", rule.getSeverity().name())
                .tag("stage", stage)
                .register(meterRegistry)
                .increment());
    }

    public void recordRateLimited() {
        Counter.builder("tinypay.security.prompt_injection.rate_limited")
                .register(meterRegistry)
                .increment();
    }

    public void recordAttachmentBlocked(String reason) {
        Counter.builder("tinypay.security.prompt_injection.attachment_blocked")
                .tag("reason", reason)
                .register(meterRegistry)
                .increment();
    }
}

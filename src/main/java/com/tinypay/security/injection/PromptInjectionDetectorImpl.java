package com.tinypay.security.injection;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 프롬프트 인젝션 검사기 구현체
 *
 * 정규식 기반 패턴 매칭으로 1차 필터링을 수행한다.
 * 모든 룰을 검사하여 다중 매칭을 지원하며, 가장 높은 심각도를 결과 severity로 사용한다.
 */
@Slf4j
@Component
public class PromptInjectionDetectorImpl implements PromptInjectionDetector {

    /** 정규식 한 번에 검사할 최대 길이와 경계 공격을 잡기 위한 중첩 길이 */
    private static final int SCAN_CHUNK_LENGTH = 10_000;
    private static final int SCAN_OVERLAP_LENGTH = 256;

    @Override
    public DetectionResult detect(String message) {
        if (message == null) {
            throw new IllegalArgumentException("message must not be null");
        }
        if (message.isBlank()) {
            return DetectionResult.safe();
        }

        String target = normalize(message);

        List<DetectionRule> matched = new ArrayList<>();
        for (DetectionRule rule : DetectionRule.values()) {
            if (matchesAnyChunk(rule, target)) {
                matched.add(rule);
            }
        }

        if (matched.isEmpty()) {
            return DetectionResult.safe();
        }

        Severity highestSeverity = matched.stream()
                .map(DetectionRule::getSeverity)
                .max(Comparator.comparingInt(Enum::ordinal))
                .orElse(Severity.LOW);

        String reason = matched.stream()
                .map(DetectionRule::getReason)
                .collect(Collectors.joining(", "));

        log.warn("[PromptInjection] detected={}, severity={}, rules={}",
                true, highestSeverity, matched);

        return DetectionResult.builder()
                .detected(true)
                .severity(highestSeverity)
                .matchedRules(matched)
                .reason(reason)
                .build();
    }

    private String normalize(String message) {
        String normalized = Normalizer.normalize(message, Normalizer.Form.NFKC);
        StringBuilder result = new StringBuilder(normalized.length());
        normalized.codePoints()
                .filter(codePoint -> Character.getType(codePoint) != Character.FORMAT)
                .forEach(result::appendCodePoint);
        return result.toString();
    }

    private boolean matchesAnyChunk(DetectionRule rule, String message) {
        if (message.length() <= SCAN_CHUNK_LENGTH) {
            return rule.getPattern().matcher(message).find();
        }

        int step = SCAN_CHUNK_LENGTH - SCAN_OVERLAP_LENGTH;
        for (int start = 0; start < message.length(); start += step) {
            int end = Math.min(start + SCAN_CHUNK_LENGTH, message.length());
            if (rule.getPattern().matcher(message.substring(start, end)).find()) {
                return true;
            }
        }
        return false;
    }
}

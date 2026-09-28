package com.tinypay.security.injection;

import java.util.regex.Pattern;

/**
 * 프롬프트 인젝션 검사 룰
 *
 * 각 룰은 정규식 패턴, 심각도, 사유를 가진다.
 * 한국어와 영어 패턴을 모두 포함하며,
 * TinyPay 결제 시스템 도메인에 특화하여 false positive를 최소화했다.
 */
public enum DetectionRule {

    // ===== 일반 LLM 인젝션 =====

    IGNORE_INSTRUCTIONS(
            "(이전[\\s\\p{P}\\p{S}]*(지시(사항)?|명령)[\\s\\p{P}\\p{S}]*무시|위의?[\\s\\p{P}\\p{S}]*(지시(사항)?|명령)[\\s\\p{P}\\p{S}]*무시|ignore[\\s\\p{P}\\p{S}]+(all[\\s\\p{P}\\p{S}]+)?(previous|prior|above)[\\s\\p{P}\\p{S}]+instructions?)",
            Severity.HIGH,
            "시스템 지시 무시 시도"
    ),

    SYSTEM_PROMPT_LEAK(
            "(시스템[\\s\\p{P}\\p{S}]*프롬프트|너의?[\\s\\p{P}\\p{S}]*프롬프트[\\s\\p{P}\\p{S}]*(보여|알려|출력)|system[\\s\\p{P}\\p{S}]+prompt|reveal[\\s\\p{P}\\p{S}]+(your|the)[\\s\\p{P}\\p{S}]+(system[\\s\\p{P}\\p{S}]+)?prompt)",
            Severity.HIGH,
            "시스템 프롬프트 추출 시도"
    ),

    JAILBREAK_DAN(
            "(DAN[\\s\\p{P}\\p{S}]*모드|do[\\s\\p{P}\\p{S}]+anything[\\s\\p{P}\\p{S}]+now|jailbreak|탈옥)",
            Severity.HIGH,
            "Jailbreak 시도 (DAN 등)"
    ),

    // ===== TinyPay 특화 =====

    RESPONSE_TYPE_MANIPULATION(
            "(response_type\\s*(을|를)?\\s*(ANSWER|UNSUPPORTED)|set\\s+response_type)",
            Severity.CRITICAL,
            "응답 분류(response_type) 조작 시도"
    ),

    COST_MANIPULATION(
            "((estimated_cost|unit_cost|total_estimated_cost)\\s*(을|를|:)?\\s*0|cost\\s*=\\s*0)",
            Severity.CRITICAL,
            "결제 금액 조작 시도"
    ),

    RISK_LEVEL_MANIPULATION(
            "(risk_level\\s*(을|를|:)?\\s*LOW|set\\s+risk_level)",
            Severity.HIGH,
            "위험도(risk_level) 조작 시도"
    ),

    SERVICE_INJECTION(
            "(service_name\\s*:\\s*['\"]|required_services\\s*:\\s*\\[|add\\s+service)",
            Severity.HIGH,
            "서비스 목록 위조 시도"
    ),

    ROLE_OVERRIDE(
            "((system|developer)[\\s\\p{P}\\p{S}]*(message|instruction|role)|시스템[\\s\\p{P}\\p{S}]*(메시지|명령|역할)|개발자[\\s\\p{P}\\p{S}]*(메시지|명령|역할))[\\s\\p{P}\\p{S}]*(로|으로|:|=|is|says?)",
            Severity.HIGH,
            "시스템 또는 개발자 역할 위조 시도"
    );

    private final Pattern pattern;
    private final Severity severity;
    private final String reason;

    DetectionRule(String regex, Severity severity, String reason) {
        this.pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        this.severity = severity;
        this.reason = reason;
    }

    public Pattern getPattern() {
        return pattern;
    }

    public Severity getSeverity() {
        return severity;
    }

    public String getReason() {
        return reason;
    }
}

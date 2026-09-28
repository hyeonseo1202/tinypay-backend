package com.tinypay.chat.service;

import com.tinypay.abuse.domain.AbuseActionType;
import com.tinypay.abuse.domain.AbuseType;
import com.tinypay.abuse.service.AbuseService;
import com.tinypay.chat.domain.ChatMessage;
import com.tinypay.dify.client.DifyClient;
import com.tinypay.dify.dto.ChatAnalysisRequest;
import com.tinypay.dify.dto.ChatAnalysisResponse;
import com.tinypay.global.exception.CustomException;
import com.tinypay.global.exception.ErrorType;
import com.tinypay.security.injection.DetectionResult;
import com.tinypay.security.injection.PromptInjectionDetector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Arrays;
import java.util.stream.Collectors;


// Dify 채팅 요청 분석 Workflow 연동 서비스
// Dify에게 보낼 요청 데이터 준비
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatAnalysisService {

    private final DifyClient difyClient;
    private final PromptInjectionDetector promptInjectionDetector;
    private final AbuseService abuseService;

    // 이전 채팅 리스트를 받아서 context를 만든 뒤, analyzeWithContext()를 호출하는 메서드
    public ChatAnalysisResponse analyze(Long userId, Long sessionId, String currentMessage, List<ChatMessage> recentMessages) {
        String context = buildContextString(recentMessages);
        return analyzeWithContext(userId, sessionId, currentMessage, context);
    }

    // 이미 만들어진 context 문자열을 가지고 Dify에게 분석 요청을 보내는 메서드
    public ChatAnalysisResponse analyzeWithContext(Long userId, Long sessionId, String currentMessage, String context) {
        log.debug("[ChatAnalysisService] 요청: userId={}, sessionId={}", userId, sessionId);

        // ===== 보안: 프롬프트 인젝션 검사 (Dify 호출 전) =====
        DetectionResult currentMessageDetection = promptInjectionDetector.detect(currentMessage);
        String userContext = extractUserContext(context);
        DetectionResult contextDetection = userContext.isBlank()
                ? DetectionResult.safe()
                : promptInjectionDetector.detect(userContext);
        DetectionResult detection = currentMessageDetection.isDetected()
                ? currentMessageDetection
                : contextDetection;
        blockIfDetected(userId, detection);

        ChatAnalysisRequest request = ChatAnalysisRequest.of(userId, sessionId, currentMessage, context);

        return difyClient.runChatAnalysis(request);
    }

    public void validateCurrentMessage(Long userId, String currentMessage) {
        if (currentMessage == null || currentMessage.isBlank()) {
            return;
        }
        blockIfDetected(userId, promptInjectionDetector.detect(currentMessage));
    }

    private void blockIfDetected(Long userId, DetectionResult detection) {
        if (detection.isDetected()) {
            log.warn("[ChatAnalysisService] 프롬프트 인젝션 감지: userId={}, severity={}, reason={}",
                    userId, detection.getSeverity(), detection.getReason());

            abuseService.record(
                    userId,
                    AbuseType.PROMPT_INJECTION,
                    AbuseActionType.BLOCKED,
                    "프롬프트 인젝션 감지: severity=" + detection.getSeverity()
                            + ", rules=" + detection.getReason()
            );

            throw new CustomException(ErrorType.PROMPT_INJECTION_DETECTED);
        }

    }

    private String extractUserContext(String context) {
        if (context == null || context.isBlank()) {
            return "";
        }
        return Arrays.stream(context.split("\\R(?=User: |Assistant: )"))
                .filter(block -> block.startsWith("User: "))
                .collect(Collectors.joining("\n"));
    }

    // DB에서 가져온 채팅 메시지 목록을 문자열로 바꾸는 메서드
    public String buildContextString(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return "";
        return messages.stream()
                .map(msg -> switch (msg.getSenderRole()) {
                    case USER      -> "User: " + msg.getContent();
                    case ASSISTANT -> "Assistant: " + msg.getContent();
                    default        -> "";
                })
                .filter(s -> !s.isBlank())
                .collect(Collectors.joining("\n"));
    }
}

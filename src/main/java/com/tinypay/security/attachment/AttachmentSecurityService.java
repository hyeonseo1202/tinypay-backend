package com.tinypay.security.attachment;

import com.tinypay.abuse.domain.AbuseActionType;
import com.tinypay.abuse.domain.AbuseType;
import com.tinypay.abuse.service.AbuseService;
import com.tinypay.chat.domain.FileAttachment;
import com.tinypay.global.exception.CustomException;
import com.tinypay.global.exception.ErrorType;
import com.tinypay.security.injection.DetectionResult;
import com.tinypay.security.injection.PromptInjectionDetector;
import com.tinypay.security.injection.PromptInjectionMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class AttachmentSecurityService {

    private static final Set<String> TEXT_APPLICATION_TYPES = Set.of(
            "application/json", "application/xml", "application/csv",
            "application/javascript", "application/x-ndjson"
    );

    private final S3Client s3Client;
    private final PromptInjectionDetector detector;
    private final AbuseService abuseService;
    private final PromptInjectionMetrics metrics;

    @Value("${cloud.aws.s3.bucket}")
    private String bucket;

    @Value("${security.prompt-injection.attachment.max-scan-bytes:3145728}")
    private long maxScanBytes;

    public void validate(Long userId, FileAttachment file) {
        validateStorageOwnership(userId, file);
        try {
            HeadObjectResponse metadata = s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(bucket).key(file.getStorageKey()).build());
            String contentType = normalizedContentType(metadata.contentType(), file.getFileType());
            if (!isTextType(contentType)) return;
            if (metadata.contentLength() == null || metadata.contentLength() > maxScanBytes) {
                block(userId, "text_file_too_large", "검사 가능한 첨부 파일 크기를 초과했습니다.");
            }

            ResponseBytes<GetObjectResponse> object = s3Client.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(bucket).key(file.getStorageKey()).build());
            DetectionResult detection = detector.detect(object.asString(StandardCharsets.UTF_8));
            if (detection.isDetected()) {
                block(userId, "injection_detected", detection.getReason());
            }
        } catch (CustomException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("[AttachmentSecurity] S3 파일 검증 실패: fileId={}, error={}", file.getId(), e.getMessage());
            block(userId, "scan_failed", "첨부 파일을 안전하게 검사할 수 없습니다.");
        }
    }

    private void validateStorageOwnership(Long userId, FileAttachment file) {
        String uploadPrefix = "uploads/" + userId + "/";
        String generatedPrefix = "generated/" + userId + "/";
        if (file.getStorageKey() == null
                || (!file.getStorageKey().startsWith(uploadPrefix)
                && !file.getStorageKey().startsWith(generatedPrefix))) {
            block(userId, "ownership_mismatch", "첨부 파일 저장 경로가 사용자와 일치하지 않습니다.");
        }
    }

    private String normalizedContentType(String actual, String declared) {
        String value = actual == null || actual.isBlank() ? declared : actual;
        return value == null ? "" : value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }

    private boolean isTextType(String contentType) {
        return contentType.startsWith("text/") || TEXT_APPLICATION_TYPES.contains(contentType);
    }

    private void block(Long userId, String reason, String detail) {
        metrics.recordAttachmentBlocked(reason);
        abuseService.record(userId, AbuseType.PROMPT_INJECTION, AbuseActionType.BLOCKED,
                "첨부 파일 보안 차단: reason=" + reason + ", detail=" + detail);
        throw new CustomException(ErrorType.ATTACHMENT_SECURITY_VIOLATION);
    }
}

package com.tinypay.global.exception;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public enum ErrorType {

    /**
     * HTTP 400 (BAD REQUEST)
     */
    REQUEST_VALIDATION_EXCEPTION(HttpStatus.BAD_REQUEST, "잘못된 요청입니다"),

    MISSING_REFRESH_TOKEN(HttpStatus.BAD_REQUEST, "refresh token이 존재하지 않습니다."),
    MISSING_ACCESS_TOKEN(HttpStatus.BAD_REQUEST, "access token이 존재하지 않습니다."),
    INVALID_TOKEN_FORMAT(HttpStatus.BAD_REQUEST, "token 형식이 올바르지 않습니다."),
    MISSING_PHONE_NUMBER(HttpStatus.BAD_REQUEST, "전화번호가 존재하지 않습니다."),
    INVALID_PHONE_NUMBER(HttpStatus.BAD_REQUEST, "전화번호 형식이 올바르지 않습니다."),
    MISSING_WALLET_PASSWORD(HttpStatus.BAD_REQUEST, "지갑 비밀번호가 존재하지 않습니다."),
    INVALID_WALLET_PASSWORD(HttpStatus.BAD_REQUEST, "지갑 비밀번호는 6자리 숫자여야 합니다."),
    WRONG_WALLET_PASSWORD(HttpStatus.BAD_REQUEST, "지갑 비밀번호가 일치하지 않습니다."),
    MISSING_VERIFICATION_CODE(HttpStatus.BAD_REQUEST, "인증번호가 존재하지 않습니다."),
    INVALID_VERIFICATION_CODE(HttpStatus.BAD_REQUEST, "인증번호가 일치하지 않습니다."),
    INVALID_ESTIMATED_COST(HttpStatus.BAD_REQUEST, "예상 금액은 0보다 커야합니다."),
    ESTIMATED_COST_MISMATCH(HttpStatus.BAD_REQUEST, "예상 결제 금액이 일치하지 않습니다."),
    PER_REQUEST_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, "건당 결제 한도를 초과했습니다."),
    MONTHLY_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, "월 결제 한도를 초과했습니다."),
    INSUFFICIENT_BALANCE(HttpStatus.BAD_REQUEST, "지갑 잔액이 부족합니다."),
    AUTO_PAYMENT_PASSWORD_REQUIRED(HttpStatus.BAD_REQUEST, "자동결제 활성화 시 지갑 비밀번호를 입력해야 합니다."),
    WALLET_LOCKED(HttpStatus.LOCKED, "지갑이 잠겨 있습니다."),
    WALLET_LOCKED_BY_PASSWORD_FAILURE(HttpStatus.LOCKED, "지갑 비밀번호 입력 횟수를 초과하여 지갑이 잠겼습니다."),
    PROMPT_INJECTION_DETECTED(HttpStatus.BAD_REQUEST, "허용되지 않은 요청 패턴이 감지되었습니다."),
    ATTACHMENT_SECURITY_VIOLATION(HttpStatus.BAD_REQUEST, "첨부 파일 보안 검증에 실패했습니다."),

    /**
     * HTTP 401 (UNAUTHORIZED)
     */
    INVALID_ID_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않은 ID Token입니다."),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않은 refresh token입니다."),
    INVALID_ACCESS_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않은 access token입니다."),

    /**
     * HTTP 403 (FORBIDDEN)
     */
    REQUEST_FORBIDDEN(HttpStatus.FORBIDDEN, "해당 요청에 대한 접근 권한이 없습니다."),
    WALLET_FORBIDDEN(HttpStatus.FORBIDDEN, "해당 지갑에 대한 접근 권한이 없습니다."),
    PHONE_NOT_VERIFIED(HttpStatus.FORBIDDEN, "전화번호 인증이 완료되지 않은 사용자입니다."),
    PAYMENT_ACCESS_FORBIDDEN(HttpStatus.FORBIDDEN, "해당 결제 내역에 접근할 수 없습니다."),
    OPS_ACCESS_FORBIDDEN(HttpStatus.FORBIDDEN, "운영 관리 API에 접근할 수 없습니다."),
    
    /**
     * HTTP 404 (NOT FOUND)
     */
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 회원입니다."),
    CHAT_SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 채팅 세션입니다."),
    AI_REQUEST_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 AI 요청입니다."),
    WALLET_NOT_FOUND(HttpStatus.NOT_FOUND, "지갑을 찾을 수 없습니다."),
    VERIFICATION_CODE_NOT_FOUND(HttpStatus.NOT_FOUND, "인증번호가 유효하지 않습니다. 인증번호를 다시 요청해주세요."),
    PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "결제 내역을 찾을 수 없습니다."),
    FILE_NOT_FOUND(HttpStatus.NOT_FOUND, "파일을 찾을 수 없습니다."),

    /**
     * HTTP 409 (CONFLICT)
     */
    DUPLICATE_NICKNAME(HttpStatus.CONFLICT, "이미 사용 중인 닉네임입니다."),
    INVALID_REQUEST_STATUS(HttpStatus.CONFLICT, "승인 가능한 상태의 요청이 아닙니다."),
    CANCEL_NOT_ALLOWED(HttpStatus.CONFLICT, "취소 가능한 상태의 요청이 아닙니다."),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT, "동일한 멱등성 키가 다른 결제 요청에 사용되었습니다."),
    IDEMPOTENCY_REQUEST_IN_PROGRESS(HttpStatus.CONFLICT, "동일한 결제 요청이 처리 중입니다."),
    IDEMPOTENCY_REQUEST_FAILED(HttpStatus.CONFLICT, "동일한 결제 요청이 이전 처리에서 실패했습니다."),
    INVALID_RECONCILIATION_STATUS(HttpStatus.CONFLICT, "현재 상태에서는 대사를 수동 재시도할 수 없습니다."),

    /**
     * HTTP 429 (Too Many Requests)
     */
    VERIFICATION_BLOCKED(HttpStatus.TOO_MANY_REQUESTS, "인증 시도 횟수를 초과했습니다."),
    VERIFICATION_CODE_COOLDOWN(HttpStatus.TOO_MANY_REQUESTS, "인증번호는 1분 후에 재발급할 수 있습니다."),
    VERIFICATION_SEND_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "인증번호 발급 횟수를 초과했습니다. 24시간 후 다시 시도해주세요."),
    PROMPT_INJECTION_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "반복된 보안 위반으로 채팅 요청이 일시 제한되었습니다."),

    /**
     * HTTP 500 (INTERNAL SERVER ERROR)
     */
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "알 수 없는 서버 에러가 발생했습니다."),
    SMS_SEND_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "인증번호 발송에 실패했습니다."),

    /**
     * HTTP 502 (BAD GATEWAY) - 외부 API 오류
     */
    DIFY_API_ERROR(HttpStatus.BAD_GATEWAY, "AI 분석 중 오류가 발생했습니다. 잠시 후 다시 시도해주세요."),
    DIFY_RESPONSE_INVALID(HttpStatus.BAD_GATEWAY, "AI 서버로부터 유효하지 않은 응답을 받았습니다."),
    ;

    private final HttpStatus httpStatus;
    private final String message;

    public int getHttpStatusCode() {
        return httpStatus.value();
    }
}

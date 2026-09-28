package com.tinypay.chat.dto;

import jakarta.validation.constraints.Size;

public record CreateChatMessageRequest(
    @Size(max = MAX_CONTENT_LENGTH, message = "메시지는 10000자를 초과할 수 없습니다.")
    String content,
    Long fileId   // 파일 첨부 시에만 포함 (선택)
) {
    public static final int MAX_CONTENT_LENGTH = 10_000;
}

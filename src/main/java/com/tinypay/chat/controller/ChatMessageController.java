package com.tinypay.chat.controller;

import com.tinypay.chat.dto.CreateChatMessageRequest;
import com.tinypay.chat.dto.CreateChatMessageResponse;
import com.tinypay.chat.dto.GetChatMessageResponse;
import com.tinypay.chat.service.ChatMessageService;
import com.tinypay.global.response.ApiResponse;
import com.tinypay.global.response.SuccessType;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/chat/sessions")
public class ChatMessageController {

    private final ChatMessageService chatMessageService;

    @PostMapping("/{sessionId}/messages")
    public ResponseEntity<ApiResponse<CreateChatMessageResponse>> createChatMessage(@PathVariable Long sessionId, @RequestAttribute("userId") Long userId, @Valid @RequestBody CreateChatMessageRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(SuccessType.CHAT_MESSAGE_CREATE_SUCCESS, chatMessageService.createChatMessage(userId, sessionId, request)));
    }

    @GetMapping("/{sessionId}/messages")
    public ResponseEntity<ApiResponse<List<GetChatMessageResponse>>> getChatMessages(@PathVariable Long sessionId, @RequestAttribute("userId") Long userId) {
        return ResponseEntity.ok(ApiResponse.success(SuccessType.GET_CHAT_MESSAGE_LIST_SUCCESS, chatMessageService.getChatMessages(userId, sessionId)));
    }
}

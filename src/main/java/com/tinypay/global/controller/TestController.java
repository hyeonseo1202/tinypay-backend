package com.tinypay.global.controller;

import com.tinypay.chat.service.DifyServiceExecutionService;
import com.tinypay.dify.domain.AiRequest;
import com.tinypay.dify.domain.AiRequestStatus;
import com.tinypay.dify.repository.AiRequestRepository;
import com.tinypay.finance.domain.PaymentLog;
import com.tinypay.finance.domain.PaymentStatus;
import com.tinypay.finance.domain.VerificationStatus;
import com.tinypay.finance.domain.Wallet;
import com.tinypay.finance.repository.PaymentLogRepository;
import com.tinypay.finance.repository.WalletRepository;
import com.tinypay.global.exception.CustomException;
import com.tinypay.global.exception.ErrorType;
import com.tinypay.global.response.ApiResponse;
import com.tinypay.global.response.SuccessType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.UUID;


 // [테스트 전용] 결제 없이 Dify 서비스 실행 트리거
@Slf4j
@Profile("local")
@RestController
@RequestMapping("/api/test")
@RequiredArgsConstructor
public class TestController {

    private final AiRequestRepository aiRequestRepository;
    private final PaymentLogRepository paymentLogRepository;
    private final WalletRepository walletRepository;
    private final DifyServiceExecutionService difyServiceExecutionService;


     // 결제 승인 없이 Dify 서비스 실행을 직접 트리거
    @Transactional
    @PostMapping("/requests/{requestId}/execute")
    public ApiResponse<String> triggerExecution(@PathVariable Long requestId) {
        AiRequest aiRequest = aiRequestRepository.findById(requestId)
                .orElseThrow(() -> new CustomException(ErrorType.AI_REQUEST_NOT_FOUND));

        log.info("[TestController] Dify 실행 트리거: requestId={}, currentStatus={}", requestId, aiRequest.getStatus());

        // WAITING_APPROVAL 상태면 결제 승인 흉내
        if (aiRequest.getStatus() == AiRequestStatus.WAITING_APPROVAL) {
            aiRequest.approve();
            aiRequest.startExecution();
        } else if (aiRequest.getStatus() != AiRequestStatus.EXECUTING) {
            return ApiResponse.success(SuccessType.PROCESS_SUCCESS,
                    "실행 불가 상태: " + aiRequest.getStatus());
        }

        // PaymentLog가 없으면 mock 생성 (테스트용)
        if (paymentLogRepository.findByRequest(aiRequest).isEmpty()) {
            Wallet wallet = walletRepository.findByUser_Id(aiRequest.getUser().getId())
                    .orElseThrow(() -> new CustomException(ErrorType.WALLET_NOT_FOUND));

            PaymentLog mockPaymentLog = PaymentLog.builder()
                    .user(aiRequest.getUser())
                    .request(aiRequest)
                    .wallet(wallet)
                    .orderId("TEST-" + UUID.randomUUID())
                    .txHash("0xtest" + requestId + UUID.randomUUID().toString().replace("-", ""))
                    .payerWalletAddress(wallet.getWalletAddress())
                    .receiverWalletAddress("0x0000000000000000000000000000000000000000")
                    .amount(aiRequest.getEstimatedTotalCost())
                    .paymentStatus(PaymentStatus.COMPLETED)
                    .verificationStatus(VerificationStatus.SUCCESS)
                    .executedAt(LocalDateTime.now())
                    .blockchainNetwork("POLYGON_AMOY")
                    .autoPaymentUsed(false)
                    .build();

            paymentLogRepository.saveAndFlush(mockPaymentLog);
            log.info("[TestController] Mock PaymentLog 생성 완료: requestId={}", requestId);
        }

        // Dify 서비스 실행 트리거 (비동기)
        difyServiceExecutionService.executeService(requestId);

        return ApiResponse.success(SuccessType.PROCESS_SUCCESS,
                "Dify 서비스 실행 트리거 완료 (requestId=" + requestId + ")");
    }
}

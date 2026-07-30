package com.tinypay.finance.service;

import com.tinypay.dify.domain.AiRequest;
import com.tinypay.finance.domain.*;
import com.tinypay.finance.repository.PaymentLogRepository;
import com.tinypay.user.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class PaymentLogService {

    private final PaymentLogRepository paymentLogRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveFailedPaymentLog(User user, AiRequest aiRequest,
                                     Wallet wallet, String orderId, String receiverWalletAddress,
                                     BigDecimal amount) {
        // 기존 FAILED 로그가 있으면 삭제 후 새로 저장 (재시도 시 Duplicate Key 방지)
        paymentLogRepository.deleteByRequestAndPaymentStatus(aiRequest, PaymentStatus.FAILED);
        PaymentLog paymentLog = PaymentLog.builder()
                .user(user)
                .request(aiRequest)
                .wallet(wallet)
                .orderId(orderId)
                .payerWalletAddress(wallet.getWalletAddress())
                .receiverWalletAddress(receiverWalletAddress)
                .amount(amount)
                .executedAt(LocalDateTime.now())
                .blockchainNetwork(wallet.getBlockchainNetwork())
                .build();
        paymentLog.fail("블록체인 결제 실패");
        paymentLogRepository.save(paymentLog);
    }
}

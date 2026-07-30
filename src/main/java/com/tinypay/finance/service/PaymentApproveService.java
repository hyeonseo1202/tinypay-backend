package com.tinypay.finance.service;

import com.tinypay.abuse.service.AbuseService;
import com.tinypay.blockchain.service.BlockchainService;
import com.tinypay.chat.service.DifyServiceExecutionService;
import com.tinypay.finance.domain.*;
import com.tinypay.finance.repository.BudgetPolicyRepository;
import com.tinypay.finance.repository.PaymentLogRepository;
import com.tinypay.finance.repository.WalletRepository;
import com.tinypay.global.exception.CustomException;
import com.tinypay.global.exception.ErrorType;
import com.tinypay.dify.domain.AiRequest;
import com.tinypay.dify.domain.AiRequestStatus;
import com.tinypay.finance.dto.request.PaymentApproveRequest;
import com.tinypay.finance.dto.response.PaymentApproveResponse;
import com.tinypay.dify.repository.AiRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentApproveService {

    private final AbuseService abuseService;
    private final AiRequestRepository aiRequestRepository;
    private final WalletRepository walletRepository;
    private final BudgetPolicyRepository budgetPolicyRepository;
    private final PaymentLogRepository paymentLogRepository;
    private final PaymentLogService paymentLogService;
    private final PaymentIdempotencyService paymentIdempotencyService;
    private final BlockchainService blockchainService;
    private final DifyServiceExecutionService difyServiceExecutionService;
    private final StringRedisTemplate stringRedisTemplate;

    private final BCryptPasswordEncoder passwordEncoder;

    @Value("${blockchain.receiver-wallet.address}")
    private String receiverWalletAddress;

    private static final int USDC_DECIMALS = 6;
    private static final int MAX_PASSWORD_FAILURES = 5;
    private static final String PASSWORD_FAIL_KEY_PREFIX = "wallet:password:fail:";

    @Transactional
    public PaymentApproveResponse paymentApprove(Long userId, Long requestId, PaymentApproveRequest request) {
        // 1. AI 요청 조회
        AiRequest aiRequest = aiRequestRepository.findById(requestId)
                .orElseThrow(() -> new CustomException(ErrorType.AI_REQUEST_NOT_FOUND));

        // 2. 소유자 확인
        if (!aiRequest.getUser().getId().equals(userId)) {
            throw new CustomException(ErrorType.REQUEST_FORBIDDEN);
        }

        // 3. 멱등성 체크 — 이미 성공한 결제면 기존 결과 반환
        Optional<PaymentLog> existingLog = paymentLogRepository.findByRequestAndPaymentStatus(aiRequest, PaymentStatus.SUCCESS);
        if (existingLog.isPresent()) {
            PaymentLog log = existingLog.get();
            return PaymentApproveResponse.builder()
                    .requestId(aiRequest.getId())
                    .status(aiRequest.getStatus().name())
                    .payment(PaymentApproveResponse.PaymentInfo.builder()
                            .paymentId(log.getId())
                            .orderId(log.getOrderId())
                            .transactionHash(log.getTxHash())
                            .amount(log.getAmount())
                            .executedAt(log.getExecutedAt())
                            .build())
                    .wallet(PaymentApproveResponse.WalletInfo.builder()
                            .balance(log.getWallet().getBalance())
                            .build())
                    .build();
        }

        // 4. 상태 확인
        if (aiRequest.getStatus() != AiRequestStatus.WAITING_APPROVAL) {
            throw new CustomException(ErrorType.INVALID_REQUEST_STATUS);
        }

        // 5. 예상 금액 일치 확인
        if (aiRequest.getEstimatedTotalCost().compareTo(request.getEstimatedCost()) != 0) {
            throw new CustomException(ErrorType.ESTIMATED_COST_MISMATCH);
        }

        BigDecimal estimatedCost = request.getEstimatedCost();

        // 6. 지갑 조회
        Wallet wallet = walletRepository.findByUser_Id(userId)
                .orElseThrow(() -> new CustomException(ErrorType.WALLET_NOT_FOUND));

        // 7. 지갑 상태 확인
        if (wallet.getWalletStatus() == WalletStatus.LOCKED) {
            throw new CustomException(ErrorType.WALLET_LOCKED);
        }

        // 8. 예산 정책 조회
        BudgetPolicy policy = budgetPolicyRepository.findByUser_IdAndDeletedAtIsNull(userId).orElse(null);
        boolean autoPaymentEnabled = policy != null && policy.isAutoPaymentEnabled();
        boolean perPaymentLimitExceeded = policy != null
                && policy.getPerRequestLimit() != null
                && estimatedCost.compareTo(policy.getPerRequestLimit()) > 0;

        // 9. 자동결제 활성화 + 한도 미초과 시 비밀번호 불필요
        //    그 외(비밀번호 비활성화 or 한도 초과)는 비밀번호 필요
        if (!autoPaymentEnabled || perPaymentLimitExceeded) {
            if (!StringUtils.hasText(request.getWalletPassword())) {
                throw new CustomException(ErrorType.MISSING_WALLET_PASSWORD);
            }
            verifyWalletPassword(userId, request.getWalletPassword(), wallet);
        }

        // 10. 월 한도 확인
        if (policy != null && policy.getMonthlyLimit() != null) {
            BigDecimal monthlySpent = paymentLogRepository
                    .sumSuccessfulAmountThisMonth(userId, PaymentStatus.SUCCESS);
            if (monthlySpent.add(estimatedCost).compareTo(policy.getMonthlyLimit()) > 0) {
                throw new CustomException(ErrorType.MONTHLY_LIMIT_EXCEEDED);
            }
        }

        // 11. 잔액 확인
        if (wallet.getBalance().compareTo(estimatedCost) < 0) {
            throw new CustomException(ErrorType.INSUFFICIENT_BALANCE);
        }

        // 12. 멱등성 키 선점 — DB unique 제약으로 하나의 요청만 결제 단계에 진입
        PaymentIdempotency idempotency;
        try {
            idempotency = paymentIdempotencyService.createClaim(
                    userId,
                    requestId,
                    request.getIdempotencyKey(),
                    estimatedCost
            );
        } catch (DataIntegrityViolationException e) {
            PaymentIdempotency existing = paymentIdempotencyService.getExistingClaim(
                    userId,
                    requestId,
                    request.getIdempotencyKey(),
                    estimatedCost
            );
            if (existing.getStatus() == PaymentIdempotencyStatus.COMPLETED
                    && existing.getPayment() != null) {
                return toResponse(aiRequest, existing.getPayment());
            }
            if (existing.getStatus() == PaymentIdempotencyStatus.FAILED) {
                throw new CustomException(ErrorType.IDEMPOTENCY_REQUEST_FAILED);
            }
            throw new CustomException(ErrorType.IDEMPOTENCY_REQUEST_IN_PROGRESS);
        }

        // 13. 요청 상태 APPROVED로 변경
        aiRequest.approve();

        // 14. 블록체인 결제 실행
        String orderId = UUID.randomUUID().toString();
        BigInteger rawAmount = estimatedCost.movePointRight(USDC_DECIMALS).toBigInteger();

        String txHash;
        try {
            txHash = blockchainService.transferUsdc(
                    orderId,
                    wallet.getWalletAddress(),
                    receiverWalletAddress,
                    rawAmount,
                    "AI_SERVICE"
            );

            blockchainService.verifyReceipt(txHash, receiverWalletAddress, rawAmount);
        } catch (Exception e) {
            log.error("[PaymentApproveService] transferUsdc 실패: walletAddress={}, amount={}, error={}",
                    wallet.getWalletAddress(), estimatedCost, e.getMessage(), e);
            aiRequest.fail("블록체인 전송 실패: " + e.getMessage());
            paymentLogService.saveFailedPaymentLog(
                    aiRequest.getUser(), aiRequest, wallet, orderId, receiverWalletAddress, estimatedCost);
            paymentIdempotencyService.fail(idempotency.getId());
            throw new CustomException(ErrorType.INTERNAL_SERVER_ERROR);
        }
        // 15. 이전 실패 기록 삭제 후 새 결제 기록 저장 (재시도 시 Unique 충돌 방지)
        paymentLogRepository.deleteByRequestAndPaymentStatus(aiRequest, PaymentStatus.FAILED);
        LocalDateTime executedAt = LocalDateTime.now();
        PaymentLog paymentLog = PaymentLog.builder()
                .user(aiRequest.getUser())
                .request(aiRequest)
                .wallet(wallet)
                .orderId(orderId)
                .txHash(txHash)
                .payerWalletAddress(wallet.getWalletAddress())
                .receiverWalletAddress(receiverWalletAddress)
                .amount(estimatedCost)
                .paymentStatus(PaymentStatus.SUCCESS)
                .executedAt(executedAt)
                .blockchainNetwork(wallet.getBlockchainNetwork())
                .build();
        paymentLogRepository.save(paymentLog);

        // 16. 멱등성 처리 완료
        paymentIdempotencyService.complete(idempotency, paymentLog);

        // 17. 지갑 잔액 차감
        wallet.updateBalance(wallet.getBalance().subtract(estimatedCost));

        // 18. 요청 상태 업데이트 (APPROVED → EXECUTING)
        aiRequest.startExecution();

        // 19. 트랜잭션 커밋 후 Dify 서비스 실행 비동기 트리거
        final Long aiRequestId = aiRequest.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                difyServiceExecutionService.executeService(aiRequestId);
            }
        });

        return toResponse(aiRequest, paymentLog);
    }

    private PaymentApproveResponse toResponse(AiRequest aiRequest, PaymentLog paymentLog) {
        return PaymentApproveResponse.builder()
                .requestId(aiRequest.getId())
                .status(aiRequest.getStatus().name())
                .payment(PaymentApproveResponse.PaymentInfo.builder()
                        .paymentId(paymentLog.getId())
                        .orderId(paymentLog.getOrderId())
                        .transactionHash(paymentLog.getTxHash())
                        .amount(paymentLog.getAmount())
                        .executedAt(paymentLog.getExecutedAt())
                        .build())
                .wallet(PaymentApproveResponse.WalletInfo.builder()
                        .balance(paymentLog.getWallet().getBalance())
                        .build())
                .build();
    }

    private void verifyWalletPassword(Long userId, String inputPassword, Wallet wallet) {
        String failKey = PASSWORD_FAIL_KEY_PREFIX + userId;

        if (!passwordEncoder.matches(inputPassword, wallet.getWalletPassword())) {
            Long failCount = stringRedisTemplate.opsForValue().increment(failKey);
            stringRedisTemplate.expire(failKey, Duration.ofHours(24));

            if (failCount != null && failCount >= MAX_PASSWORD_FAILURES) {
                abuseService.recordRateLimitViolation(userId, "결제 비밀번호 " + MAX_PASSWORD_FAILURES + "회 실패: userId=" + userId);
                blockchainService.lockWalletForBruteForce(userId);
                stringRedisTemplate.delete(failKey);
                throw new CustomException(ErrorType.WALLET_LOCKED_BY_PASSWORD_FAILURE);
            }

            throw new CustomException(ErrorType.WRONG_WALLET_PASSWORD);
        }

        stringRedisTemplate.delete(failKey);
    }
}

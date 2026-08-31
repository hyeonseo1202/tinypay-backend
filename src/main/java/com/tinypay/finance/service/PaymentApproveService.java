package com.tinypay.finance.service;

import com.tinypay.abuse.service.AbuseService;
import com.tinypay.blockchain.service.BlockchainService;
import com.tinypay.chat.service.DifyServiceExecutionService;
import com.tinypay.dify.domain.AiRequest;
import com.tinypay.dify.domain.AiRequestStatus;
import com.tinypay.dify.repository.AiRequestRepository;
import com.tinypay.finance.domain.*;
import com.tinypay.finance.dto.request.PaymentApproveRequest;
import com.tinypay.finance.dto.response.PaymentApproveResponse;
import com.tinypay.finance.repository.BudgetPolicyRepository;
import com.tinypay.finance.repository.PaymentLogRepository;
import com.tinypay.finance.repository.WalletRepository;
import com.tinypay.global.exception.CustomException;
import com.tinypay.global.exception.ErrorType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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

    private static final int USDC_DECIMALS = 6;
    private static final int MAX_PASSWORD_FAILURES = 5;
    private static final String PASSWORD_FAIL_KEY_PREFIX = "wallet:password:fail:";

    private final AbuseService abuseService;
    private final AiRequestRepository aiRequestRepository;
    private final WalletRepository walletRepository;
    private final BudgetPolicyRepository budgetPolicyRepository;
    private final PaymentLogRepository paymentLogRepository;
    private final PaymentIdempotencyService paymentIdempotencyService;
    private final BlockchainService blockchainService;
    private final DifyServiceExecutionService difyServiceExecutionService;
    private final StringRedisTemplate stringRedisTemplate;
    private final BCryptPasswordEncoder passwordEncoder;
    private final PlatformTransactionManager transactionManager;

    @Value("${blockchain.receiver-wallet.address}")
    private String receiverWalletAddress;

    /**
     * DB 락이 필요한 준비/확정 단계만 짧은 트랜잭션으로 실행한다.
     * 블록체인 전송과 영수증 조회는 두 트랜잭션 사이에서 수행한다.
     */
    public PaymentApproveResponse paymentApprove(Long userId, Long requestId, PaymentApproveRequest request) {
        Preparation preparation = requiredTransaction().execute(
                status -> preparePayment(userId, requestId, request)
        );
        if (preparation == null) {
            throw new IllegalStateException("결제 준비 결과가 없습니다.");
        }
        if (preparation.existingResponse() != null) {
            return preparation.existingResponse();
        }

        String txHash = null;
        PaymentApproveResponse response;
        try {
            BigInteger rawAmount = preparation.amount()
                    .movePointRight(USDC_DECIMALS)
                    .toBigIntegerExact();

            // DB 트랜잭션 밖에서 블록체인 응답을 기다린다.
            txHash = blockchainService.transferUsdc(
                    preparation.orderId(),
                    preparation.walletAddress(),
                    receiverWalletAddress,
                    rawAmount,
                    "AI_SERVICE"
            );

            String submittedTxHash = txHash;
            requiredTransaction().executeWithoutResult(
                    status -> recordPaid(preparation.paymentId(), submittedTxHash)
            );

            boolean receiptVerified = blockchainService.verifyReceipt(
                    txHash,
                    receiverWalletAddress,
                    rawAmount
            );
            if (!receiptVerified) {
                throw new IllegalStateException("블록체인 영수증 검증에 실패했습니다.");
            }

            response = requiredTransaction().execute(
                    status -> completePayment(preparation)
            );
            if (response == null) {
                throw new IllegalStateException("결제 확정 결과가 없습니다.");
            }
        } catch (Exception e) {
            handleBlockchainFailure(preparation, txHash, e);
            throw new CustomException(ErrorType.INTERNAL_SERVER_ERROR);
        }

        // 결제 확정 트랜잭션이 커밋된 뒤 후속 서비스를 실행한다.
        // 비동기 작업 제출 실패가 이미 완료된 결제 응답을 실패로 바꾸지는 않도록 분리한다.
        try {
            difyServiceExecutionService.executeService(preparation.aiRequestId());
        } catch (Exception e) {
            log.error(
                    "[PaymentApproveService] 결제 후 서비스 실행 요청 실패: aiRequestId={}",
                    preparation.aiRequestId(), e
            );
        }
        return response;
    }

    private Preparation preparePayment(Long userId, Long requestId, PaymentApproveRequest request) {
        AiRequest aiRequest = aiRequestRepository.findByIdWithLock(requestId)
                .orElseThrow(() -> new CustomException(ErrorType.AI_REQUEST_NOT_FOUND));

        if (!aiRequest.getUser().getId().equals(userId)) {
            throw new CustomException(ErrorType.REQUEST_FORBIDDEN);
        }

        Optional<PaymentLog> existingLog = paymentLogRepository
                .findFirstByRequestAndPaymentStatusIn(aiRequest, PaymentStatus.successfulStatuses());
        if (existingLog.isPresent()) {
            return Preparation.existing(toResponse(aiRequest, existingLog.get()));
        }
        if (aiRequest.getStatus() != AiRequestStatus.WAITING_APPROVAL) {
            throw new CustomException(ErrorType.INVALID_REQUEST_STATUS);
        }
        if (aiRequest.getEstimatedTotalCost().compareTo(request.getEstimatedCost()) != 0) {
            throw new CustomException(ErrorType.ESTIMATED_COST_MISMATCH);
        }

        BigDecimal estimatedCost = request.getEstimatedCost();
        Wallet wallet = walletRepository.findByUserIdWithLock(userId)
                .orElseThrow(() -> new CustomException(ErrorType.WALLET_NOT_FOUND));
        if (wallet.getWalletStatus() == WalletStatus.LOCKED) {
            throw new CustomException(ErrorType.WALLET_LOCKED);
        }

        BudgetPolicy policy = budgetPolicyRepository
                .findByUser_IdAndDeletedAtIsNull(userId)
                .orElse(null);
        boolean autoPaymentEnabled = policy != null && policy.isAutoPaymentEnabled();
        boolean perPaymentLimitExceeded = policy != null
                && policy.getPerRequestLimit() != null
                && estimatedCost.compareTo(policy.getPerRequestLimit()) > 0;

        if (!autoPaymentEnabled || perPaymentLimitExceeded) {
            if (!StringUtils.hasText(request.getWalletPassword())) {
                throw new CustomException(ErrorType.MISSING_WALLET_PASSWORD);
            }
            verifyWalletPassword(userId, request.getWalletPassword(), wallet);
        }

        if (policy != null && policy.getMonthlyLimit() != null) {
            BigDecimal monthlyCommitted = paymentLogRepository.sumSuccessfulAmountThisMonth(
                    userId,
                    PaymentStatus.budgetCommittedStatuses()
            );
            if (monthlyCommitted.add(estimatedCost).compareTo(policy.getMonthlyLimit()) > 0) {
                throw new CustomException(ErrorType.MONTHLY_LIMIT_EXCEEDED);
            }
        }
        if (!wallet.canWithdraw(estimatedCost)) {
            throw new CustomException(ErrorType.INSUFFICIENT_BALANCE);
        }

        PaymentIdempotency idempotency;
        try {
            idempotency = paymentIdempotencyService.createClaim(
                    userId, requestId, request.getIdempotencyKey(), estimatedCost
            );
        } catch (DataIntegrityViolationException e) {
            PaymentIdempotency existing = paymentIdempotencyService.getExistingClaim(
                    userId, requestId, request.getIdempotencyKey(), estimatedCost
            );
            if (existing.getStatus() == PaymentIdempotencyStatus.COMPLETED
                    && existing.getPayment() != null) {
                Long existingPaymentId = existing.getPayment().getId();
                PaymentLog completedPayment = paymentLogRepository.findById(existingPaymentId)
                        .orElseThrow(() -> new IllegalStateException(
                                "완료된 결제 기록을 찾을 수 없습니다: " + existingPaymentId
                        ));
                return Preparation.existing(toResponse(aiRequest, completedPayment));
            }
            if (existing.getStatus() == PaymentIdempotencyStatus.FAILED) {
                throw new CustomException(ErrorType.IDEMPOTENCY_REQUEST_FAILED);
            }
            throw new CustomException(ErrorType.IDEMPOTENCY_REQUEST_IN_PROGRESS);
        }

        paymentLogRepository.deleteByRequestAndPaymentStatus(aiRequest, PaymentStatus.FAILED);
        String orderId = UUID.randomUUID().toString();
        PaymentLog paymentLog = PaymentLog.builder()
                .user(aiRequest.getUser())
                .request(aiRequest)
                .wallet(wallet)
                .orderId(orderId)
                .payerWalletAddress(wallet.getWalletAddress())
                .receiverWalletAddress(receiverWalletAddress)
                .amount(estimatedCost)
                .executedAt(LocalDateTime.now())
                .blockchainNetwork(wallet.getBlockchainNetwork())
                .autoPaymentUsed(autoPaymentEnabled && !perPaymentLimitExceeded)
                .build();
        paymentLogRepository.saveAndFlush(paymentLog);
        paymentLog.approve();
        aiRequest.approve();

        // 성공 확정 전 선차감하여, 락 해제 뒤에도 다른 결제가 같은 잔액을 쓰지 못하게 한다.
        wallet.withdraw(estimatedCost);

        return new Preparation(
                null, aiRequest.getId(), wallet.getId(), paymentLog.getId(), idempotency.getId(),
                orderId, wallet.getWalletAddress(), estimatedCost
        );
    }

    private void recordPaid(Long paymentId, String txHash) {
        PaymentLog payment = paymentLogRepository.findByIdWithLock(paymentId)
                .orElseThrow(() -> new IllegalStateException("결제 기록을 찾을 수 없습니다: " + paymentId));
        if (payment.getPaymentStatus() == PaymentStatus.APPROVED) {
            payment.markPaid(txHash);
        }
    }

    private PaymentApproveResponse completePayment(Preparation preparation) {
        PaymentLog payment = paymentLogRepository.findByIdWithLock(preparation.paymentId())
                .orElseThrow(() -> new IllegalStateException(
                        "결제 기록을 찾을 수 없습니다: " + preparation.paymentId()
                ));
        Wallet wallet = walletRepository.findByIdWithLock(preparation.walletId())
                .orElseThrow(() -> new IllegalStateException(
                        "지갑을 찾을 수 없습니다: " + preparation.walletId()
                ));
        AiRequest aiRequest = aiRequestRepository.findByIdWithLock(preparation.aiRequestId())
                .orElseThrow(() -> new IllegalStateException(
                        "AI 요청을 찾을 수 없습니다: " + preparation.aiRequestId()
                ));

        payment.markVerified();
        payment.complete();
        paymentIdempotencyService.complete(preparation.idempotencyId(), payment);
        aiRequest.startExecution();
        return toResponse(aiRequest, payment, wallet.getBalance());
    }

    private void handleBlockchainFailure(Preparation preparation, String txHash, Exception cause) {
        log.error(
                "[PaymentApproveService] 블록체인 결제 실패: paymentId={}, txHash={}, error={}",
                preparation.paymentId(), txHash, cause.getMessage(), cause
        );

        try {
            requiredTransaction().executeWithoutResult(status -> {
                PaymentLog payment = paymentLogRepository.findByIdWithLock(preparation.paymentId())
                        .orElseThrow(() -> new IllegalStateException(
                                "결제 기록을 찾을 수 없습니다: " + preparation.paymentId()
                        ));
                Wallet wallet = walletRepository.findByIdWithLock(preparation.walletId())
                        .orElseThrow(() -> new IllegalStateException(
                                "지갑을 찾을 수 없습니다: " + preparation.walletId()
                        ));
                AiRequest aiRequest = aiRequestRepository.findByIdWithLock(preparation.aiRequestId())
                        .orElseThrow(() -> new IllegalStateException(
                                "AI 요청을 찾을 수 없습니다: " + preparation.aiRequestId()
                        ));

                if (StringUtils.hasText(txHash)
                        && payment.getPaymentStatus() == PaymentStatus.APPROVED) {
                    payment.markPaid(txHash);
                }

                String reason = "블록체인 결제 실패: " + cause.getMessage();
                payment.fail(reason);
                aiRequest.fail(reason);

                if (!StringUtils.hasText(txHash)) {
                    // 체인에 제출되지 않은 것이 확실한 경우에만 선점 금액을 복원한다.
                    wallet.deposit(preparation.amount());
                }
            });
        } finally {
            paymentIdempotencyService.fail(preparation.idempotencyId());
        }
    }

    private PaymentApproveResponse toResponse(AiRequest aiRequest, PaymentLog paymentLog) {
        return toResponse(aiRequest, paymentLog, paymentLog.getWallet().getBalance());
    }

    private PaymentApproveResponse toResponse(
            AiRequest aiRequest,
            PaymentLog paymentLog,
            BigDecimal walletBalance
    ) {
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
                        .balance(walletBalance)
                        .build())
                .build();
    }

    private TransactionTemplate requiredTransaction() {
        return new TransactionTemplate(transactionManager);
    }

    private void verifyWalletPassword(Long userId, String inputPassword, Wallet wallet) {
        String failKey = PASSWORD_FAIL_KEY_PREFIX + userId;
        if (!passwordEncoder.matches(inputPassword, wallet.getWalletPassword())) {
            Long failCount = stringRedisTemplate.opsForValue().increment(failKey);
            stringRedisTemplate.expire(failKey, Duration.ofHours(24));

            if (failCount != null && failCount >= MAX_PASSWORD_FAILURES) {
                abuseService.recordRateLimitViolation(
                        userId,
                        "결제 비밀번호 " + MAX_PASSWORD_FAILURES + "회 실패: userId=" + userId
                );
                blockchainService.lockWalletForBruteForce(userId);
                stringRedisTemplate.delete(failKey);
                throw new CustomException(ErrorType.WALLET_LOCKED_BY_PASSWORD_FAILURE);
            }
            throw new CustomException(ErrorType.WRONG_WALLET_PASSWORD);
        }
        stringRedisTemplate.delete(failKey);
    }

    private record Preparation(
            PaymentApproveResponse existingResponse,
            Long aiRequestId,
            Long walletId,
            Long paymentId,
            Long idempotencyId,
            String orderId,
            String walletAddress,
            BigDecimal amount
    ) {
        private static Preparation existing(PaymentApproveResponse response) {
            return new Preparation(response, null, null, null, null, null, null, null);
        }
    }
}

package com.tinypay.finance.service;

import com.tinypay.blockchain.verification.FailReason;
import com.tinypay.blockchain.verification.ReceiptVerifier;
import com.tinypay.blockchain.verification.VerificationResult;
import com.tinypay.finance.domain.*;
import com.tinypay.finance.event.ReconciliationAlertEvent;
import com.tinypay.finance.repository.PaymentLogRepository;
import com.tinypay.finance.repository.TxVerificationLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentReconciliationService {

    private static final int USDC_DECIMALS = 6;
    private static final Set<PaymentStatus> RECONCILABLE_PAYMENT_STATUSES =
            Stream.concat(
                    Stream.of(PaymentStatus.PAID, PaymentStatus.VERIFIED, PaymentStatus.FAILED),
                    PaymentStatus.successfulStatuses().stream()
            ).collect(Collectors.toUnmodifiableSet());
    private static final Set<ReconciliationStatus> CLAIMABLE_RECONCILIATION_STATUSES = Set.of(
            ReconciliationStatus.PENDING,
            ReconciliationStatus.RETRY_REQUIRED
    );

    private final PaymentLogRepository paymentLogRepository;
    private final TxVerificationLogRepository txVerificationLogRepository;
    private final ReceiptVerifier receiptVerifier;
    private final PaymentReconciliationMetrics metrics;
    private final ReconciliationAlertOutboxService outboxService;

    @Value("${payment.reconciliation.batch-size:50}")
    private int batchSize;

    @Value("${payment.reconciliation.retry-delay-minutes:5}")
    private long retryDelayMinutes;

    @Value("${payment.reconciliation.stale-after-minutes:10}")
    private long staleAfterMinutes;

    @Value("${payment.reconciliation.max-attempts:5}")
    private int maxAttempts;

    @Value("${blockchain.mock-usdc-address}")
    private String tokenAddress;

    @Transactional
    public List<Long> claimBatch() {
        LocalDateTime now = LocalDateTime.now();
        int resetCount = paymentLogRepository.resetStaleReconciliations(
                ReconciliationStatus.PROCESSING,
                ReconciliationStatus.RETRY_REQUIRED,
                now.minusMinutes(staleAfterMinutes),
                now,
                "대사 처리 제한 시간을 초과하여 재시도 대기 상태로 전환"
        );
        if (resetCount > 0) {
            log.warn("[PaymentReconciliation] 중단된 대사 {}건을 재시도 대상으로 복구", resetCount);
        }

        List<PaymentLog> candidates = paymentLogRepository.findReconciliationCandidatesForUpdate(
                RECONCILABLE_PAYMENT_STATUSES,
                CLAIMABLE_RECONCILIATION_STATUSES,
                now,
                PageRequest.of(0, batchSize)
        );
        candidates.forEach(PaymentLog::startReconciliation);
        return candidates.stream().map(PaymentLog::getId).toList();
    }

    @Transactional
    public void reconcileOne(Long paymentId) {
        PaymentLog payment = paymentLogRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("대상 결제를 찾을 수 없습니다: " + paymentId));

        if (payment.getReconciliationStatus() != ReconciliationStatus.PROCESSING) {
            log.info("[PaymentReconciliation] 이미 처리된 대사 건너뜀: paymentId={}, status={}",
                    paymentId, payment.getReconciliationStatus());
            return;
        }

        try {
            BigInteger rawAmount = payment.getAmount()
                    .movePointRight(USDC_DECIMALS)
                    .toBigIntegerExact();
            VerificationResult result = receiptVerifier.verifyForReconciliation(
                    payment.getTxHash(),
                    payment.getReceiverWalletAddress(),
                    rawAmount
            );

            saveVerificationLog(payment, result);
            if (result.isValid()) {
                payment.markReconciliationMatched();
                metrics.record(ReconciliationStatus.MATCHED);
                log.info("[PaymentReconciliation] 대사 일치: paymentId={}, txHash={}",
                        paymentId, payment.getTxHash());
            } else {
                payment.markReconciliationMismatched(result.getDetail());
                metrics.record(ReconciliationStatus.MISMATCHED);
                saveAlertOutbox(payment, result.getDetail());
                log.warn("[PaymentReconciliation] 대사 불일치: paymentId={}, reason={}, detail={}",
                        paymentId, result.getReason(), result.getDetail());
            }
        } catch (Exception e) {
            String detail = "대사 중 외부 시스템 오류: " + e.getMessage();
            if (payment.getReconciliationAttempts() >= maxAttempts) {
                payment.markReconciliationRetryExhausted(detail);
                metrics.record(ReconciliationStatus.RETRY_EXHAUSTED);
                saveAlertOutbox(payment, detail);
                log.error("[PaymentReconciliation] 최대 재시도 초과: paymentId={}, attempts={}",
                        paymentId, payment.getReconciliationAttempts(), e);
            } else {
                Duration retryDelay = Duration.ofMinutes(retryDelayMinutes)
                        .multipliedBy(payment.getReconciliationAttempts());
                payment.markReconciliationRetry(
                        detail,
                        LocalDateTime.now().plus(retryDelay)
                );
                metrics.record(ReconciliationStatus.RETRY_REQUIRED);
                log.warn("[PaymentReconciliation] 재시도 예약: paymentId={}, attempts={}, delay={}",
                        paymentId, payment.getReconciliationAttempts(), retryDelay, e);
            }
        }
    }

    private void saveVerificationLog(PaymentLog payment, VerificationResult result) {
        txVerificationLogRepository.save(TxVerificationLog.builder()
                .user(payment.getUser())
                .payment(payment)
                .txHash(payment.getTxHash())
                .verificationStatus(toVerificationStatus(result.getReason()))
                .failedAtStep(result.isValid() ? null : result.getFailedStep())
                .expectedAmount(payment.getAmount())
                .expectedReceiver(payment.getReceiverWalletAddress())
                .tokenAddress(tokenAddress)
                .isOfficialToken(result.getReason() != FailReason.INVALID_CONTRACT)
                .detail(result.getDetail())
                .blockchainNetwork(payment.getBlockchainNetwork())
                .build());
    }

    private void saveAlertOutbox(PaymentLog payment, String detail) {
        outboxService.enqueue(new ReconciliationAlertEvent(
                payment.getId(), payment.getTxHash(), payment.getAmount(),
                payment.getReconciliationStatus(), payment.getReconciliationAttempts(), detail
        ));
    }

    private TxVerificationStatus toVerificationStatus(FailReason reason) {
        return switch (reason) {
            case SUCCESS -> TxVerificationStatus.PASSED;
            case REPLAY_ATTACK -> TxVerificationStatus.FAILED_REPLAY;
            case TRANSACTION_FAILED -> TxVerificationStatus.FAILED_TX;
            case INVALID_CONTRACT -> TxVerificationStatus.FAILED_CONTRACT;
            case INVALID_RECIPIENT -> TxVerificationStatus.FAILED_RECEIVER;
            case INSUFFICIENT_PAYMENT -> TxVerificationStatus.FAILED_AMOUNT;
        };
    }
}

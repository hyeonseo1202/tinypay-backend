package com.tinypay.finance.domain;

import com.tinypay.global.common.entity.BaseTimeEntity;
import com.tinypay.dify.domain.AiRequest;
import com.tinypay.user.domain.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Entity
@Table(name = "payment_log")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentLog extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "request_id", nullable = false)
    private AiRequest request;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "wallet_id", nullable = false)
    private Wallet wallet;

    @Column(name = "order_id", nullable = false, unique = true)
    private String orderId;

    @Column(name = "tx_hash", unique = true)
    private String txHash;

    @Column(name = "payer_wallet_address", nullable = false)
    private String payerWalletAddress;

    @Column(name = "receiver_wallet_address", nullable = false)
    private String receiverWalletAddress;

    @Column(nullable = false, precision = 18, scale = 6)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false)
    private PaymentStatus paymentStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false)
    private VerificationStatus verificationStatus;

    @Column(name = "executed_at", nullable = false)
    private LocalDateTime executedAt;

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "failed_at")
    private LocalDateTime failedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "failed_from_status")
    private PaymentStatus failedFromStatus;

    @Column(name = "failure_reason", length = 1000)
    private String failureReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "reconciliation_status")
    private ReconciliationStatus reconciliationStatus;

    @Column(name = "reconciliation_attempts", nullable = false)
    private int reconciliationAttempts;

    @Column(name = "reconciliation_started_at")
    private LocalDateTime reconciliationStartedAt;

    @Column(name = "last_reconciled_at")
    private LocalDateTime lastReconciledAt;

    @Column(name = "next_reconciliation_at")
    private LocalDateTime nextReconciliationAt;

    @Column(name = "reconciliation_error", length = 1000)
    private String reconciliationError;

    @Column(name = "blockchain_network", nullable = false)
    private String blockchainNetwork;

    @Column(name = "auto_payment_used", nullable = false)
    private boolean autoPaymentUsed;

    @Builder
    public PaymentLog(User user, AiRequest request, Wallet wallet, String orderId, String txHash, String payerWalletAddress, String receiverWalletAddress, BigDecimal amount, PaymentStatus paymentStatus, VerificationStatus verificationStatus, LocalDateTime executedAt, LocalDateTime verifiedAt, String blockchainNetwork, Boolean autoPaymentUsed
    ) {
        this.user = user;
        this.request = request;
        this.wallet = wallet;
        this.orderId = orderId;
        this.txHash = txHash;
        this.payerWalletAddress = payerWalletAddress;
        this.receiverWalletAddress = receiverWalletAddress;
        this.amount = amount == null ? BigDecimal.ZERO.setScale(6) : amount;
        this.paymentStatus = paymentStatus == null ? PaymentStatus.REQUESTED : paymentStatus;
        this.verificationStatus = verificationStatus == null ? VerificationStatus.PENDING : verificationStatus;
        this.executedAt = executedAt == null ? LocalDateTime.now() : executedAt;
        this.verifiedAt = verifiedAt;
        this.blockchainNetwork = blockchainNetwork == null ? "POLYGON_AMOY" : blockchainNetwork;
        this.autoPaymentUsed = autoPaymentUsed != null && autoPaymentUsed;
        this.reconciliationStatus = ReconciliationStatus.PENDING;
    }

    public void approve() {
        transition(PaymentStatus.REQUESTED, PaymentStatus.APPROVED);
        this.approvedAt = LocalDateTime.now();
    }

    public void markPaid(String txHash) {
        if (!StringUtils.hasText(txHash)) {
            throw new IllegalArgumentException("결제 트랜잭션 해시가 필요합니다.");
        }
        transition(PaymentStatus.APPROVED, PaymentStatus.PAID);
        this.txHash = txHash;
        this.paidAt = LocalDateTime.now();
    }

    public void markVerified() {
        transition(PaymentStatus.PAID, PaymentStatus.VERIFIED);
        this.verificationStatus = VerificationStatus.SUCCESS;
        this.verifiedAt = LocalDateTime.now();
    }

    public void complete() {
        transition(PaymentStatus.VERIFIED, PaymentStatus.COMPLETED);
        this.completedAt = LocalDateTime.now();
    }

    public void fail(String reason) {
        if (paymentStatus.isSuccessful() || paymentStatus == PaymentStatus.FAILED) {
            throw new IllegalStateException("완료되거나 실패한 결제는 실패 상태로 변경할 수 없습니다.");
        }
        this.failedFromStatus = this.paymentStatus;
        this.paymentStatus = PaymentStatus.FAILED;
        this.failureReason = StringUtils.hasText(reason) ? reason : "알 수 없는 결제 실패";
        this.failedAt = LocalDateTime.now();
        this.verificationStatus = VerificationStatus.FAILED;
    }

    public void startReconciliation() {
        if (!StringUtils.hasText(txHash)) {
            throw new IllegalStateException("트랜잭션 해시가 없는 결제는 대사할 수 없습니다.");
        }
        this.reconciliationStatus = ReconciliationStatus.PROCESSING;
        this.reconciliationAttempts++;
        this.reconciliationStartedAt = LocalDateTime.now();
        this.reconciliationError = null;
    }

    public void markReconciliationMatched() {
        requireReconciliationProcessing();
        this.reconciliationStatus = ReconciliationStatus.MATCHED;
        this.lastReconciledAt = LocalDateTime.now();
        this.nextReconciliationAt = null;
        this.reconciliationError = null;
    }

    public void markReconciliationMismatched(String detail) {
        requireReconciliationProcessing();
        this.reconciliationStatus = ReconciliationStatus.MISMATCHED;
        this.lastReconciledAt = LocalDateTime.now();
        this.nextReconciliationAt = null;
        this.reconciliationError = detail;
    }

    public void markReconciliationRetry(String detail, LocalDateTime nextAttemptAt) {
        requireReconciliationProcessing();
        this.reconciliationStatus = ReconciliationStatus.RETRY_REQUIRED;
        this.lastReconciledAt = LocalDateTime.now();
        this.nextReconciliationAt = nextAttemptAt;
        this.reconciliationError = detail;
    }

    public void markReconciliationRetryExhausted(String detail) {
        requireReconciliationProcessing();
        this.reconciliationStatus = ReconciliationStatus.RETRY_EXHAUSTED;
        this.lastReconciledAt = LocalDateTime.now();
        this.nextReconciliationAt = null;
        this.reconciliationError = detail;
    }

    public void requestManualReconciliation() {
        if (reconciliationStatus != ReconciliationStatus.MISMATCHED
                && reconciliationStatus != ReconciliationStatus.RETRY_EXHAUSTED
                && reconciliationStatus != ReconciliationStatus.RETRY_REQUIRED) {
            throw new IllegalStateException("수동 재시도를 요청할 수 없는 대사 상태입니다: " + reconciliationStatus);
        }
        this.reconciliationStatus = ReconciliationStatus.PENDING;
        this.reconciliationAttempts = 0;
        this.reconciliationStartedAt = null;
        this.nextReconciliationAt = null;
        this.reconciliationError = null;
    }

    private void requireReconciliationProcessing() {
        if (reconciliationStatus != ReconciliationStatus.PROCESSING) {
            throw new IllegalStateException("처리 중인 대사만 결과를 기록할 수 있습니다.");
        }
    }

    private void transition(PaymentStatus expected, PaymentStatus next) {
        if (paymentStatus != expected) {
            throw new IllegalStateException(
                    "허용되지 않은 결제 상태 전이입니다: " + paymentStatus + " -> " + next
            );
        }
        this.paymentStatus = next;
    }
}

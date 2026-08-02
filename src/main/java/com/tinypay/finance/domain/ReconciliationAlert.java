package com.tinypay.finance.domain;

import com.tinypay.global.common.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.math.BigDecimal;
import com.tinypay.finance.event.ReconciliationAlertEvent;

@Getter
@Entity
@Table(name = "reconciliation_alert", uniqueConstraints =
        @UniqueConstraint(name = "uk_reconciliation_alert_event_key", columnNames = "event_key"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReconciliationAlert extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_key", nullable = false, updatable = false, length = 100)
    private String eventKey;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private Long paymentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reconciliation_status", nullable = false, updatable = false)
    private ReconciliationStatus reconciliationStatus;

    @Column(name = "reconciliation_attempt", nullable = false, updatable = false)
    private int reconciliationAttempt;

    @Column(name = "tx_hash", length = 100)
    private String txHash;

    @Column(name = "amount", precision = 18, scale = 6)
    private BigDecimal amount;

    @Column(name = "alert_detail", length = 1000)
    private String detail;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_status", nullable = false)
    private ReconciliationAlertStatus deliveryStatus;

    @Column(name = "delivery_attempts", nullable = false)
    private int deliveryAttempts;

    @Column(name = "processing_started_at")
    private LocalDateTime processingStartedAt;

    @Column(name = "next_retry_at")
    private LocalDateTime nextRetryAt;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    private ReconciliationAlert(String eventKey, Long paymentId,
                                ReconciliationStatus reconciliationStatus, int reconciliationAttempt) {
        this.eventKey = eventKey;
        this.paymentId = paymentId;
        this.reconciliationStatus = reconciliationStatus;
        this.reconciliationAttempt = reconciliationAttempt;
        this.deliveryStatus = ReconciliationAlertStatus.PENDING;
    }

    public static ReconciliationAlert pending(String eventKey, Long paymentId,
                                               ReconciliationStatus status, int attempt) {
        return new ReconciliationAlert(eventKey, paymentId, status, attempt);
    }

    public static ReconciliationAlert pending(ReconciliationAlertEvent event) {
        ReconciliationAlert alert = new ReconciliationAlert(
                event.eventKey(), event.paymentId(), event.status(), event.attempt());
        alert.txHash = event.txHash();
        alert.amount = event.amount();
        alert.detail = event.detail();
        return alert;
    }

    public void startDelivery() {
        if (deliveryStatus != ReconciliationAlertStatus.PENDING
                && deliveryStatus != ReconciliationAlertStatus.FAILED) {
            throw new IllegalStateException("발송을 시작할 수 없는 알림 상태입니다: " + deliveryStatus);
        }
        this.deliveryStatus = ReconciliationAlertStatus.PROCESSING;
        this.deliveryAttempts++;
        this.processingStartedAt = LocalDateTime.now();
        this.nextRetryAt = null;
        this.lastError = null;
    }

    public void markSent() {
        this.deliveryStatus = ReconciliationAlertStatus.SENT;
        this.sentAt = LocalDateTime.now();
        this.processingStartedAt = null;
        this.nextRetryAt = null;
        this.lastError = null;
    }

    public void markFailed(String error, LocalDateTime nextRetryAt) {
        this.deliveryStatus = ReconciliationAlertStatus.FAILED;
        this.processingStartedAt = null;
        this.nextRetryAt = nextRetryAt;
        this.lastError = error;
    }

    public void markExhausted(String error) {
        this.deliveryStatus = ReconciliationAlertStatus.EXHAUSTED;
        this.processingStartedAt = null;
        this.nextRetryAt = null;
        this.lastError = error;
    }

    public ReconciliationAlertEvent toEvent() {
        return new ReconciliationAlertEvent(
                paymentId, txHash, amount, reconciliationStatus, reconciliationAttempt, detail);
    }
}

package com.tinypay.finance.domain;

import com.tinypay.finance.event.ReconciliationAlertEvent;
import com.tinypay.global.common.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Entity
@Table(name = "reconciliation_alert_outbox", uniqueConstraints =
        @UniqueConstraint(name = "uk_reconciliation_outbox_event_key", columnNames = "event_key"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReconciliationAlertOutbox extends BaseTimeEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_key", nullable = false, updatable = false, length = 100)
    private String eventKey;
    @Column(name = "payment_id", nullable = false, updatable = false)
    private Long paymentId;
    @Column(name = "tx_hash", length = 100, updatable = false)
    private String txHash;
    @Column(precision = 18, scale = 6, updatable = false)
    private BigDecimal amount;
    @Enumerated(EnumType.STRING)
    @Column(name = "reconciliation_status", nullable = false, updatable = false)
    private ReconciliationStatus reconciliationStatus;
    @Column(name = "reconciliation_attempt", nullable = false, updatable = false)
    private int reconciliationAttempt;
    @Column(name = "event_detail", length = 1000, updatable = false)
    private String detail;

    @Enumerated(EnumType.STRING)
    @Column(name = "publish_status", nullable = false)
    private ReconciliationOutboxStatus publishStatus;
    @Column(name = "publish_attempts", nullable = false)
    private int publishAttempts;
    @Column(name = "processing_started_at")
    private LocalDateTime processingStartedAt;
    @Column(name = "next_retry_at")
    private LocalDateTime nextRetryAt;
    @Column(name = "published_at")
    private LocalDateTime publishedAt;
    @Column(name = "last_error", length = 1000)
    private String lastError;

    public static ReconciliationAlertOutbox pending(ReconciliationAlertEvent event) {
        ReconciliationAlertOutbox outbox = new ReconciliationAlertOutbox();
        outbox.eventKey = event.eventKey();
        outbox.paymentId = event.paymentId();
        outbox.txHash = event.txHash();
        outbox.amount = event.amount();
        outbox.reconciliationStatus = event.status();
        outbox.reconciliationAttempt = event.attempt();
        outbox.detail = event.detail();
        outbox.publishStatus = ReconciliationOutboxStatus.PENDING;
        return outbox;
    }

    public void startPublishing() {
        if (publishStatus != ReconciliationOutboxStatus.PENDING
                && publishStatus != ReconciliationOutboxStatus.FAILED) {
            throw new IllegalStateException("발행할 수 없는 Outbox 상태입니다: " + publishStatus);
        }
        publishStatus = ReconciliationOutboxStatus.PROCESSING;
        publishAttempts++;
        processingStartedAt = LocalDateTime.now();
        nextRetryAt = null;
        lastError = null;
    }

    public void markPublished() {
        publishStatus = ReconciliationOutboxStatus.PUBLISHED;
        publishedAt = LocalDateTime.now();
        processingStartedAt = null;
        nextRetryAt = null;
        lastError = null;
    }

    public void markFailed(String error, LocalDateTime retryAt) {
        publishStatus = ReconciliationOutboxStatus.FAILED;
        processingStartedAt = null;
        nextRetryAt = retryAt;
        lastError = error;
    }

    public void markExhausted(String error) {
        publishStatus = ReconciliationOutboxStatus.EXHAUSTED;
        processingStartedAt = null;
        nextRetryAt = null;
        lastError = error;
    }

    public ReconciliationAlertEvent toEvent() {
        return new ReconciliationAlertEvent(paymentId, txHash, amount,
                reconciliationStatus, reconciliationAttempt, detail);
    }
}

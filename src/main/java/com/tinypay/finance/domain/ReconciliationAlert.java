package com.tinypay.finance.domain;

import com.tinypay.global.common.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

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

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_status", nullable = false)
    private ReconciliationAlertStatus deliveryStatus;

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

    public void retry() {
        this.deliveryStatus = ReconciliationAlertStatus.PENDING;
        this.lastError = null;
    }

    public void markSent() {
        this.deliveryStatus = ReconciliationAlertStatus.SENT;
        this.sentAt = LocalDateTime.now();
        this.lastError = null;
    }

    public void markFailed(String error) {
        this.deliveryStatus = ReconciliationAlertStatus.FAILED;
        this.lastError = error;
    }
}

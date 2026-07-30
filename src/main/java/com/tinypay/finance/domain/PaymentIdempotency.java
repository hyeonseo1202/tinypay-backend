package com.tinypay.finance.domain;

import com.tinypay.global.common.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Getter
@Entity
@Table(
        name = "payment_idempotency",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_payment_idempotency_user_key",
                columnNames = {"user_id", "idempotency_key"}
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentIdempotency extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "request_id", nullable = false)
    private Long requestId;

    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    @Column(name = "request_amount", nullable = false, precision = 18, scale = 6)
    private BigDecimal requestAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentIdempotencyStatus status;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id", unique = true)
    private PaymentLog payment;

    private PaymentIdempotency(Long userId, Long requestId, String idempotencyKey, BigDecimal requestAmount) {
        this.userId = userId;
        this.requestId = requestId;
        this.idempotencyKey = idempotencyKey;
        this.requestAmount = requestAmount;
        this.status = PaymentIdempotencyStatus.PROCESSING;
    }

    public static PaymentIdempotency processing(
            Long userId,
            Long requestId,
            String idempotencyKey,
            BigDecimal requestAmount
    ) {
        return new PaymentIdempotency(userId, requestId, idempotencyKey, requestAmount);
    }

    public boolean matches(Long requestId, BigDecimal requestAmount) {
        return this.requestId.equals(requestId)
                && this.requestAmount.compareTo(requestAmount) == 0;
    }

    public void complete(PaymentLog payment) {
        this.payment = payment;
        this.status = PaymentIdempotencyStatus.COMPLETED;
    }

    public void fail() {
        this.status = PaymentIdempotencyStatus.FAILED;
    }
}

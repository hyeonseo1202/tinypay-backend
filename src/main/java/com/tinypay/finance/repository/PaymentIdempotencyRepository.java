package com.tinypay.finance.repository;

import com.tinypay.finance.domain.PaymentIdempotency;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentIdempotencyRepository extends JpaRepository<PaymentIdempotency, Long> {

    @EntityGraph(attributePaths = {"payment", "payment.wallet", "payment.request"})
    Optional<PaymentIdempotency> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);
}

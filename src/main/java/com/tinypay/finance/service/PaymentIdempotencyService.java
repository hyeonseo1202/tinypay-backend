package com.tinypay.finance.service;

import com.tinypay.finance.domain.PaymentIdempotency;
import com.tinypay.finance.domain.PaymentIdempotencyStatus;
import com.tinypay.finance.domain.PaymentLog;
import com.tinypay.finance.repository.PaymentIdempotencyRepository;
import com.tinypay.global.exception.CustomException;
import com.tinypay.global.exception.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class PaymentIdempotencyService {

    private final PaymentIdempotencyRepository paymentIdempotencyRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaymentIdempotency createClaim(
            Long userId,
            Long requestId,
            String idempotencyKey,
            BigDecimal requestAmount
    ) {
        return paymentIdempotencyRepository.saveAndFlush(
                PaymentIdempotency.processing(userId, requestId, idempotencyKey, requestAmount)
        );
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public PaymentIdempotency getExistingClaim(
            Long userId,
            Long requestId,
            String idempotencyKey,
            BigDecimal requestAmount
    ) {
        PaymentIdempotency existing = paymentIdempotencyRepository
                .findByUserIdAndIdempotencyKey(userId, idempotencyKey)
                .orElseThrow(() -> new CustomException(ErrorType.IDEMPOTENCY_REQUEST_IN_PROGRESS));

        if (!existing.matches(requestId, requestAmount)) {
            throw new CustomException(ErrorType.IDEMPOTENCY_KEY_REUSED);
        }
        return existing;
    }

    @Transactional
    public void complete(PaymentIdempotency idempotency, PaymentLog payment) {
        idempotency.complete(payment);
        paymentIdempotencyRepository.save(idempotency);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(Long idempotencyId) {
        paymentIdempotencyRepository.findById(idempotencyId)
                .filter(value -> value.getStatus() == PaymentIdempotencyStatus.PROCESSING)
                .ifPresent(value -> {
                    value.fail();
                    paymentIdempotencyRepository.save(value);
                });
    }
}

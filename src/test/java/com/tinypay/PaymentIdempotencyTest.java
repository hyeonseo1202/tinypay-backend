package com.tinypay.finance.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentIdempotencyTest {

    @Test
    void 동일한_요청과_금액이면_일치한다() {
        PaymentIdempotency idempotency = PaymentIdempotency.processing(
                1L, 10L, "payment-key", new BigDecimal("12.340000")
        );

        assertThat(idempotency.matches(10L, new BigDecimal("12.34"))).isTrue();
    }

    @Test
    void 같은_키라도_요청이나_금액이_다르면_일치하지_않는다() {
        PaymentIdempotency idempotency = PaymentIdempotency.processing(
                1L, 10L, "payment-key", new BigDecimal("12.340000")
        );

        assertThat(idempotency.matches(11L, new BigDecimal("12.34"))).isFalse();
        assertThat(idempotency.matches(10L, new BigDecimal("99.99"))).isFalse();
    }

    @Test
    void 실패하면_상태가_FAILED로_변경된다() {
        PaymentIdempotency idempotency = PaymentIdempotency.processing(
                1L, 10L, "payment-key", new BigDecimal("12.34")
        );

        idempotency.fail();

        assertThat(idempotency.getStatus()).isEqualTo(PaymentIdempotencyStatus.FAILED);
    }
}

package com.tinypay.finance.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentStateMachineTest {

    @Test
    void 결제는_정해진_순서로_완료된다() {
        PaymentLog payment = requestedPayment();

        payment.approve();
        payment.markPaid("0x-transaction");
        payment.markVerified();
        payment.complete();

        assertThat(payment.getPaymentStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(payment.getVerificationStatus()).isEqualTo(VerificationStatus.SUCCESS);
        assertThat(payment.getTxHash()).isEqualTo("0x-transaction");
        assertThat(payment.getApprovedAt()).isNotNull();
        assertThat(payment.getPaidAt()).isNotNull();
        assertThat(payment.getVerifiedAt()).isNotNull();
        assertThat(payment.getCompletedAt()).isNotNull();
    }

    @Test
    void 승인되지_않은_결제는_PAID로_변경할_수_없다() {
        PaymentLog payment = requestedPayment();

        assertThatThrownBy(() -> payment.markPaid("0x-transaction"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REQUESTED -> PAID");
    }

    @Test
    void 결제_실패_시_실패한_단계와_원인을_기록한다() {
        PaymentLog payment = requestedPayment();
        payment.approve();
        payment.markPaid("0x-transaction");

        payment.fail("영수증 검증 실패");

        assertThat(payment.getPaymentStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailedFromStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(payment.getFailureReason()).isEqualTo("영수증 검증 실패");
        assertThat(payment.getFailedAt()).isNotNull();
        assertThat(payment.getVerificationStatus()).isEqualTo(VerificationStatus.FAILED);
    }

    @Test
    void 완료된_결제는_다른_상태로_변경할_수_없다() {
        PaymentLog payment = requestedPayment();
        payment.approve();
        payment.markPaid("0x-transaction");
        payment.markVerified();
        payment.complete();

        assertThatThrownBy(() -> payment.fail("뒤늦은 실패"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(payment::complete)
                .isInstanceOf(IllegalStateException.class);
    }

    private PaymentLog requestedPayment() {
        return PaymentLog.builder()
                .orderId("order-1")
                .payerWalletAddress("0x-payer")
                .receiverWalletAddress("0x-receiver")
                .amount(new BigDecimal("10.000000"))
                .blockchainNetwork("POLYGON_AMOY")
                .build();
    }
}

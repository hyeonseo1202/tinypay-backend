package com.tinypay.finance.service;

import com.tinypay.finance.domain.*;
import com.tinypay.finance.dto.response.ReconciliationRetryResponse;
import com.tinypay.finance.dto.response.ReconciliationSummaryResponse;
import com.tinypay.finance.repository.PaymentLogRepository;
import com.tinypay.finance.repository.TxVerificationLogRepository;
import com.tinypay.global.exception.CustomException;
import com.tinypay.global.exception.ErrorType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentReconciliationMonitoringServiceTest {

    @Mock PaymentLogRepository paymentLogRepository;
    @Mock TxVerificationLogRepository txVerificationLogRepository;
    @Mock PaymentReconciliationMetrics metrics;
    private PaymentReconciliationMonitoringService service;

    @BeforeEach
    void setUp() {
        service = new PaymentReconciliationMonitoringService(
                paymentLogRepository, txVerificationLogRepository, metrics);
    }

    @Test
    void 상태별_대사_건수를_요약한다() {
        when(paymentLogRepository.countByReconciliationStatus(ReconciliationStatus.PENDING)).thenReturn(2L);
        when(paymentLogRepository.countByReconciliationStatusIsNull()).thenReturn(1L);
        when(paymentLogRepository.countByReconciliationStatus(ReconciliationStatus.PROCESSING)).thenReturn(3L);
        when(paymentLogRepository.countByReconciliationStatus(ReconciliationStatus.MATCHED)).thenReturn(4L);
        when(paymentLogRepository.countByReconciliationStatus(ReconciliationStatus.MISMATCHED)).thenReturn(5L);
        when(paymentLogRepository.countByReconciliationStatus(ReconciliationStatus.RETRY_REQUIRED)).thenReturn(6L);
        when(paymentLogRepository.countByReconciliationStatus(ReconciliationStatus.RETRY_EXHAUSTED)).thenReturn(7L);

        assertThat(service.getSummary())
                .isEqualTo(new ReconciliationSummaryResponse(3, 3, 4, 5, 6, 7));
    }

    @Test
    void 실패한_대사를_수동_재시도_대기로_전환한다() {
        PaymentLog payment = payment();
        ReflectionTestUtils.setField(payment, "id", 10L);
        payment.startReconciliation();
        payment.markReconciliationMismatched("amount mismatch");
        when(paymentLogRepository.findById(10L)).thenReturn(Optional.of(payment));

        ReconciliationRetryResponse result = service.requestRetry(10L);

        assertThat(result.reconciliationStatus()).isEqualTo(ReconciliationStatus.PENDING.name());
        assertThat(payment.getReconciliationAttempts()).isZero();
        verify(metrics).recordManualRetry();
    }

    @Test
    void 정상_상태의_대사는_수동_재시도를_거부한다() {
        when(paymentLogRepository.findById(10L)).thenReturn(Optional.of(payment()));

        assertThatThrownBy(() -> service.requestRetry(10L))
                .isInstanceOfSatisfying(CustomException.class,
                        exception -> assertThat(exception.getErrorType())
                                .isEqualTo(ErrorType.INVALID_RECONCILIATION_STATUS));
        verifyNoInteractions(metrics);
    }

    private PaymentLog payment() {
        return PaymentLog.builder()
                .orderId("order-10").txHash("0x-tx")
                .payerWalletAddress("0x-payer").receiverWalletAddress("0x-receiver")
                .amount(new BigDecimal("10.000000"))
                .paymentStatus(PaymentStatus.COMPLETED)
                .blockchainNetwork("POLYGON_AMOY").build();
    }
}

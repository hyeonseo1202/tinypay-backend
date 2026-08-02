package com.tinypay.finance.service;

import com.tinypay.blockchain.verification.FailReason;
import com.tinypay.blockchain.verification.ReceiptVerifier;
import com.tinypay.blockchain.verification.VerificationResult;
import com.tinypay.finance.domain.PaymentLog;
import com.tinypay.finance.domain.PaymentStatus;
import com.tinypay.finance.domain.ReconciliationStatus;
import com.tinypay.finance.repository.PaymentLogRepository;
import com.tinypay.finance.repository.TxVerificationLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentReconciliationServiceTest {

    @Mock
    private PaymentLogRepository paymentLogRepository;

    @Mock
    private TxVerificationLogRepository txVerificationLogRepository;

    @Mock
    private ReceiptVerifier receiptVerifier;

    @Mock
    private PaymentReconciliationMetrics metrics;

    @Mock
    private ReconciliationAlertOutboxService outboxService;

    private PaymentReconciliationService service;

    @BeforeEach
    void setUp() {
        service = new PaymentReconciliationService(
                paymentLogRepository,
                txVerificationLogRepository,
                receiptVerifier,
                metrics,
                outboxService
        );
        ReflectionTestUtils.setField(service, "batchSize", 50);
        ReflectionTestUtils.setField(service, "retryDelayMinutes", 5L);
        ReflectionTestUtils.setField(service, "staleAfterMinutes", 10L);
        ReflectionTestUtils.setField(service, "maxAttempts", 5);
        ReflectionTestUtils.setField(service, "tokenAddress", "0x-token");
    }

    @Test
    void 배치_대상을_선점하면_PROCESSING으로_변경한다() {
        PaymentLog payment = payment();
        ReflectionTestUtils.setField(payment, "id", 1L);
        when(paymentLogRepository.findReconciliationCandidatesForUpdate(
                anyCollection(), anyCollection(), any(LocalDateTime.class), any(Pageable.class)
        )).thenReturn(List.of(payment));

        List<Long> claimedIds = service.claimBatch();

        assertThat(claimedIds).containsExactly(1L);
        assertThat(payment.getReconciliationStatus()).isEqualTo(ReconciliationStatus.PROCESSING);
        assertThat(payment.getReconciliationAttempts()).isEqualTo(1);
    }

    @Test
    void 온체인_거래가_일치하면_MATCHED로_기록한다() {
        PaymentLog payment = processingPayment();
        when(paymentLogRepository.findById(1L)).thenReturn(Optional.of(payment));
        when(receiptVerifier.verifyForReconciliation(
                "0x-tx", "0x-receiver", new BigInteger("10500000")
        )).thenReturn(VerificationResult.success());

        service.reconcileOne(1L);

        assertThat(payment.getReconciliationStatus()).isEqualTo(ReconciliationStatus.MATCHED);
        assertThat(payment.getLastReconciledAt()).isNotNull();
        verify(txVerificationLogRepository).save(any());
        verify(metrics).record(ReconciliationStatus.MATCHED);
    }

    @Test
    void 온체인_금액이_다르면_MISMATCHED로_기록한다() {
        PaymentLog payment = processingPayment();
        when(paymentLogRepository.findById(1L)).thenReturn(Optional.of(payment));
        when(receiptVerifier.verifyForReconciliation(anyString(), anyString(), any(BigInteger.class)))
                .thenReturn(VerificationResult.fail(
                        FailReason.INSUFFICIENT_PAYMENT,
                        "expected=10.5, actual=5"
                ));

        service.reconcileOne(1L);

        assertThat(payment.getReconciliationStatus()).isEqualTo(ReconciliationStatus.MISMATCHED);
        assertThat(payment.getReconciliationError()).contains("actual=5");
        verify(txVerificationLogRepository).save(any());
        verify(metrics).record(ReconciliationStatus.MISMATCHED);
        verify(outboxService).enqueue(any(com.tinypay.finance.event.ReconciliationAlertEvent.class));
    }

    @Test
    void RPC_오류는_다음_대사_시각을_지정해_재시도한다() {
        PaymentLog payment = processingPayment();
        when(paymentLogRepository.findById(1L)).thenReturn(Optional.of(payment));
        when(receiptVerifier.verifyForReconciliation(anyString(), anyString(), any(BigInteger.class)))
                .thenThrow(new RuntimeException("RPC timeout"));

        service.reconcileOne(1L);

        assertThat(payment.getReconciliationStatus()).isEqualTo(ReconciliationStatus.RETRY_REQUIRED);
        assertThat(payment.getNextReconciliationAt()).isAfter(LocalDateTime.now());
        assertThat(payment.getReconciliationError()).contains("RPC timeout");
        verify(txVerificationLogRepository, never()).save(any());
        verify(metrics).record(ReconciliationStatus.RETRY_REQUIRED);
    }

    @Test
    void 최대_재시도를_초과하면_수동_확인_대상으로_남긴다() {
        ReflectionTestUtils.setField(service, "maxAttempts", 1);
        PaymentLog payment = processingPayment();
        when(paymentLogRepository.findById(1L)).thenReturn(Optional.of(payment));
        when(receiptVerifier.verifyForReconciliation(anyString(), anyString(), any(BigInteger.class)))
                .thenThrow(new RuntimeException("RPC unavailable"));

        service.reconcileOne(1L);

        assertThat(payment.getReconciliationStatus())
                .isEqualTo(ReconciliationStatus.RETRY_EXHAUSTED);
        assertThat(payment.getNextReconciliationAt()).isNull();
        verify(metrics).record(ReconciliationStatus.RETRY_EXHAUSTED);
        verify(outboxService).enqueue(any(com.tinypay.finance.event.ReconciliationAlertEvent.class));
    }

    private PaymentLog processingPayment() {
        PaymentLog payment = payment();
        ReflectionTestUtils.setField(payment, "id", 1L);
        payment.startReconciliation();
        return payment;
    }

    private PaymentLog payment() {
        return PaymentLog.builder()
                .orderId("order-1")
                .txHash("0x-tx")
                .payerWalletAddress("0x-payer")
                .receiverWalletAddress("0x-receiver")
                .amount(new BigDecimal("10.500000"))
                .paymentStatus(PaymentStatus.COMPLETED)
                .blockchainNetwork("POLYGON_AMOY")
                .build();
    }
}

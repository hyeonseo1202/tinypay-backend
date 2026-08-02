package com.tinypay.finance.service;

import com.tinypay.finance.domain.ReconciliationAlert;
import com.tinypay.finance.domain.ReconciliationAlertStatus;
import com.tinypay.finance.domain.ReconciliationStatus;
import com.tinypay.finance.event.ReconciliationAlertEvent;
import com.tinypay.finance.repository.ReconciliationAlertRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReconciliationAlertDeliveryServiceTest {

    @Mock ReconciliationAlertRepository alertRepository;
    @Mock ReconciliationAlertSender alertSender;
    @Mock PaymentReconciliationMetrics metrics;

    @Test
    void 새_대사_경고를_발송하고_SENT로_기록한다() {
        ReconciliationAlertEvent event = event();
        when(alertRepository.findByEventKey(event.eventKey())).thenReturn(Optional.empty());
        when(alertRepository.save(any(ReconciliationAlert.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        ReconciliationAlertDeliveryService service = service();

        service.deliver(event);

        verify(alertSender).send(event);
        verify(metrics).recordAlertDelivery("success");
    }

    @Test
    void 이미_발송된_이벤트는_중복_발송하지_않는다() {
        ReconciliationAlertEvent event = event();
        ReconciliationAlert alert = ReconciliationAlert.pending(event);
        alert.startDelivery();
        alert.markSent();
        when(alertRepository.findByEventKey(event.eventKey())).thenReturn(Optional.of(alert));

        service().deliver(event);

        verifyNoInteractions(alertSender);
        verify(metrics).recordAlertDuplicate();
    }

    @Test
    void 웹훅_실패는_예외를_전파하지_않고_FAILED로_기록한다() {
        ReconciliationAlertEvent event = event();
        ReconciliationAlert alert = ReconciliationAlert.pending(event);
        when(alertRepository.findByEventKey(event.eventKey())).thenReturn(Optional.of(alert));
        doThrow(new RuntimeException("webhook timeout")).when(alertSender).send(event);

        service().deliver(event);

        assertThat(alert.getDeliveryStatus()).isEqualTo(ReconciliationAlertStatus.FAILED);
        assertThat(alert.getLastError()).contains("timeout");
        assertThat(alert.getNextRetryAt()).isAfter(LocalDateTime.now());
        assertThat(alert.getDeliveryAttempts()).isEqualTo(1);
        verify(metrics).recordAlertDelivery("failure");
        verify(metrics).recordAlertRetry("scheduled");
    }

    @Test
    void 최대_시도_횟수에_도달하면_EXHAUSTED로_종료한다() {
        ReconciliationAlertEvent event = event();
        ReconciliationAlert alert = ReconciliationAlert.pending(event);
        when(alertRepository.findByEventKey(event.eventKey())).thenReturn(Optional.of(alert));
        doThrow(new RuntimeException("webhook down")).when(alertSender).send(event);
        ReconciliationAlertDeliveryService service = service();
        ReflectionTestUtils.setField(service, "maxAttempts", 1);

        service.deliver(event);

        assertThat(alert.getDeliveryStatus()).isEqualTo(ReconciliationAlertStatus.EXHAUSTED);
        assertThat(alert.getNextRetryAt()).isNull();
        verify(metrics).recordAlertRetry("exhausted");
    }

    @Test
    void 재시도_대상을_선점하면_PROCESSING으로_변경하고_시도횟수를_증가시킨다() {
        ReconciliationAlert alert = ReconciliationAlert.pending(event());
        ReflectionTestUtils.setField(alert, "id", 10L);
        alert.startDelivery();
        alert.markFailed("timeout", LocalDateTime.now().minusSeconds(1));
        when(alertRepository.findRetryCandidatesForUpdate(
                eq(ReconciliationAlertStatus.FAILED), any(LocalDateTime.class), any()))
                .thenReturn(List.of(alert));

        List<Long> claimed = service().claimRetryBatch();

        assertThat(claimed).containsExactly(10L);
        assertThat(alert.getDeliveryStatus()).isEqualTo(ReconciliationAlertStatus.PROCESSING);
        assertThat(alert.getDeliveryAttempts()).isEqualTo(2);
    }

    @Test
    void 두번째_실패는_기본_간격의_두배_뒤로_예약한다() {
        ReconciliationAlert alert = ReconciliationAlert.pending(event());
        ReflectionTestUtils.setField(alert, "id", 10L);
        alert.startDelivery();
        alert.markFailed("first failure", LocalDateTime.now().minusSeconds(1));
        alert.startDelivery();
        when(alertRepository.findById(10L)).thenReturn(Optional.of(alert));
        doThrow(new RuntimeException("second failure")).when(alertSender).send(any());
        LocalDateTime before = LocalDateTime.now();

        service().deliverClaimed(10L);

        assertThat(alert.getDeliveryStatus()).isEqualTo(ReconciliationAlertStatus.FAILED);
        assertThat(alert.getNextRetryAt())
                .isBetween(before.plusSeconds(120), LocalDateTime.now().plusSeconds(121));
    }

    private ReconciliationAlertDeliveryService service() {
        ReconciliationAlertDeliveryService service =
                new ReconciliationAlertDeliveryService(alertRepository, alertSender, metrics);
        ReflectionTestUtils.setField(service, "maxAttempts", 5);
        ReflectionTestUtils.setField(service, "retryBaseSeconds", 60L);
        ReflectionTestUtils.setField(service, "batchSize", 50);
        ReflectionTestUtils.setField(service, "staleAfterMinutes", 10L);
        return service;
    }

    private ReconciliationAlertEvent event() {
        return new ReconciliationAlertEvent(
                1L, "0x-tx", new BigDecimal("10.000000"),
                ReconciliationStatus.MISMATCHED, 1, "amount mismatch");
    }
}

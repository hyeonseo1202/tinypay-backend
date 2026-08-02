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

import java.math.BigDecimal;
import java.util.Optional;

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
        ReconciliationAlert alert = ReconciliationAlert.pending(
                event.eventKey(), event.paymentId(), event.status(), event.attempt());
        alert.markSent();
        when(alertRepository.findByEventKey(event.eventKey())).thenReturn(Optional.of(alert));

        service().deliver(event);

        verifyNoInteractions(alertSender);
        verify(metrics).recordAlertDuplicate();
    }

    @Test
    void 웹훅_실패는_예외를_전파하지_않고_FAILED로_기록한다() {
        ReconciliationAlertEvent event = event();
        ReconciliationAlert alert = ReconciliationAlert.pending(
                event.eventKey(), event.paymentId(), event.status(), event.attempt());
        when(alertRepository.findByEventKey(event.eventKey())).thenReturn(Optional.of(alert));
        doThrow(new RuntimeException("webhook timeout")).when(alertSender).send(event);

        service().deliver(event);

        assertThat(alert.getDeliveryStatus()).isEqualTo(ReconciliationAlertStatus.FAILED);
        assertThat(alert.getLastError()).contains("timeout");
        verify(metrics).recordAlertDelivery("failure");
    }

    private ReconciliationAlertDeliveryService service() {
        return new ReconciliationAlertDeliveryService(alertRepository, alertSender, metrics);
    }

    private ReconciliationAlertEvent event() {
        return new ReconciliationAlertEvent(
                1L, "0x-tx", new BigDecimal("10.000000"),
                ReconciliationStatus.MISMATCHED, 1, "amount mismatch");
    }
}

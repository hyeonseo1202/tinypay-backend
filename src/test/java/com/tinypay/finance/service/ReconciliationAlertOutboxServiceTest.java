package com.tinypay.finance.service;

import com.tinypay.finance.domain.*;
import com.tinypay.finance.event.ReconciliationAlertEvent;
import com.tinypay.finance.repository.ReconciliationAlertOutboxRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReconciliationAlertOutboxServiceTest {

    @Mock ReconciliationAlertOutboxRepository outboxRepository;
    @Mock ReconciliationAlertDeliveryService deliveryService;
    @Mock PaymentReconciliationMetrics metrics;

    @Test
    void 대사_경고를_PENDING_Outbox로_저장한다() {
        ReconciliationAlertEvent event = event();
        when(outboxRepository.findByEventKey(event.eventKey())).thenReturn(Optional.empty());

        service().enqueue(event);

        verify(outboxRepository).save(argThat(outbox ->
                outbox.getEventKey().equals(event.eventKey())
                        && outbox.getPublishStatus() == ReconciliationOutboxStatus.PENDING));
        verify(metrics).recordOutbox("enqueued");
    }

    @Test
    void 발행_대상을_선점하면_PROCESSING으로_변경한다() {
        ReconciliationAlertOutbox outbox = outbox();
        ReflectionTestUtils.setField(outbox, "id", 10L);
        when(outboxRepository.findPublishCandidatesForUpdate(anyCollection(), any(), any()))
                .thenReturn(List.of(outbox));

        List<Long> ids = service().claimBatch();

        assertThat(ids).containsExactly(10L);
        assertThat(outbox.getPublishStatus()).isEqualTo(ReconciliationOutboxStatus.PROCESSING);
        assertThat(outbox.getPublishAttempts()).isEqualTo(1);
    }

    @Test
    void 알림_시스템에_인계되면_Outbox를_PUBLISHED로_변경한다() {
        ReconciliationAlertOutbox outbox = processingOutbox();
        when(outboxRepository.findById(10L)).thenReturn(Optional.of(outbox));

        service().publishOne(10L);

        verify(deliveryService).deliver(outbox.toEvent());
        assertThat(outbox.getPublishStatus()).isEqualTo(ReconciliationOutboxStatus.PUBLISHED);
        verify(metrics).recordOutbox("published");
    }

    @Test
    void 인계_실패는_지수_백오프로_재시도를_예약한다() {
        ReconciliationAlertOutbox outbox = processingOutbox();
        when(outboxRepository.findById(10L)).thenReturn(Optional.of(outbox));
        doThrow(new RuntimeException("database unavailable")).when(deliveryService).deliver(any());

        service().publishOne(10L);

        assertThat(outbox.getPublishStatus()).isEqualTo(ReconciliationOutboxStatus.FAILED);
        assertThat(outbox.getNextRetryAt()).isAfter(LocalDateTime.now());
        verify(metrics).recordOutbox("retry_scheduled");
    }

    private ReconciliationAlertOutboxService service() {
        ReconciliationAlertOutboxService service =
                new ReconciliationAlertOutboxService(outboxRepository, deliveryService, metrics);
        ReflectionTestUtils.setField(service, "batchSize", 50);
        ReflectionTestUtils.setField(service, "maxAttempts", 10);
        ReflectionTestUtils.setField(service, "retryBaseSeconds", 30L);
        ReflectionTestUtils.setField(service, "staleAfterMinutes", 10L);
        return service;
    }

    private ReconciliationAlertOutbox processingOutbox() {
        ReconciliationAlertOutbox outbox = outbox();
        ReflectionTestUtils.setField(outbox, "id", 10L);
        outbox.startPublishing();
        return outbox;
    }

    private ReconciliationAlertOutbox outbox() {
        return ReconciliationAlertOutbox.pending(event());
    }

    private ReconciliationAlertEvent event() {
        return new ReconciliationAlertEvent(1L, "0x-tx", new BigDecimal("10.000000"),
                ReconciliationStatus.MISMATCHED, 1, "amount mismatch");
    }
}

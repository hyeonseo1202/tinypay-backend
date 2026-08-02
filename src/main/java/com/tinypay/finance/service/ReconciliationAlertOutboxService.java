package com.tinypay.finance.service;

import com.tinypay.finance.domain.*;
import com.tinypay.finance.event.ReconciliationAlertEvent;
import com.tinypay.finance.repository.ReconciliationAlertOutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReconciliationAlertOutboxService {

    private final ReconciliationAlertOutboxRepository outboxRepository;
    private final ReconciliationAlertDeliveryService deliveryService;
    private final PaymentReconciliationMetrics metrics;

    @Value("${payment.reconciliation.outbox.batch-size:50}") private int batchSize;
    @Value("${payment.reconciliation.outbox.max-attempts:10}") private int maxAttempts;
    @Value("${payment.reconciliation.outbox.retry-base-seconds:30}") private long retryBaseSeconds;
    @Value("${payment.reconciliation.outbox.stale-after-minutes:10}") private long staleAfterMinutes;

    @Transactional
    public void enqueue(ReconciliationAlertEvent event) {
        if (outboxRepository.findByEventKey(event.eventKey()).isEmpty()) {
            outboxRepository.save(ReconciliationAlertOutbox.pending(event));
            metrics.recordOutbox("enqueued");
        } else {
            metrics.recordOutbox("duplicate");
        }
    }

    @Transactional
    public List<Long> claimBatch() {
        LocalDateTime now = LocalDateTime.now();
        int recovered = outboxRepository.resetStaleProcessing(
                ReconciliationOutboxStatus.PROCESSING, ReconciliationOutboxStatus.FAILED,
                now.minusMinutes(staleAfterMinutes), now,
                "Outbox 처리 제한 시간을 초과하여 재시도 대상으로 복구");
        if (recovered > 0) {
            metrics.recordOutboxRecovered(recovered);
        }

        List<ReconciliationAlertOutbox> candidates = outboxRepository.findPublishCandidatesForUpdate(
                Set.of(ReconciliationOutboxStatus.PENDING, ReconciliationOutboxStatus.FAILED),
                now, PageRequest.of(0, batchSize));
        candidates.forEach(ReconciliationAlertOutbox::startPublishing);
        return candidates.stream().map(ReconciliationAlertOutbox::getId).toList();
    }

    @Transactional
    public void publishOne(Long outboxId) {
        ReconciliationAlertOutbox outbox = outboxRepository.findById(outboxId)
                .orElseThrow(() -> new IllegalArgumentException("Outbox 이벤트를 찾을 수 없습니다: " + outboxId));
        if (outbox.getPublishStatus() != ReconciliationOutboxStatus.PROCESSING) {
            return;
        }

        try {
            deliveryService.deliver(outbox.toEvent());
            outbox.markPublished();
            metrics.recordOutbox("published");
        } catch (Exception e) {
            if (outbox.getPublishAttempts() >= maxAttempts) {
                outbox.markExhausted(e.getMessage());
                metrics.recordOutbox("exhausted");
                log.error("[ReconciliationOutbox] 최대 발행 시도 초과: id={}", outboxId, e);
                return;
            }
            Duration delay = retryDelay(outbox.getPublishAttempts());
            outbox.markFailed(e.getMessage(), LocalDateTime.now().plus(delay));
            metrics.recordOutbox("retry_scheduled");
            log.warn("[ReconciliationOutbox] 발행 재시도 예약: id={}, delay={}", outboxId, delay, e);
        }
    }

    private Duration retryDelay(int attempts) {
        long multiplier = 1L << Math.min(Math.max(attempts - 1, 0), 20);
        return Duration.ofSeconds(retryBaseSeconds).multipliedBy(multiplier);
    }
}

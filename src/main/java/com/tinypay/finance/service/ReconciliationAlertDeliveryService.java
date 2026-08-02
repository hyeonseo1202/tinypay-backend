package com.tinypay.finance.service;

import com.tinypay.finance.domain.ReconciliationAlert;
import com.tinypay.finance.domain.ReconciliationAlertStatus;
import com.tinypay.finance.event.ReconciliationAlertEvent;
import com.tinypay.finance.repository.ReconciliationAlertRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReconciliationAlertDeliveryService {

    private final ReconciliationAlertRepository alertRepository;
    private final ReconciliationAlertSender alertSender;
    private final PaymentReconciliationMetrics metrics;

    @Value("${payment.reconciliation.alert.max-attempts:5}")
    private int maxAttempts;

    @Value("${payment.reconciliation.alert.retry-base-seconds:60}")
    private long retryBaseSeconds;

    @Value("${payment.reconciliation.alert.batch-size:50}")
    private int batchSize;

    @Value("${payment.reconciliation.alert.stale-after-minutes:10}")
    private long staleAfterMinutes;

    @Transactional
    public void deliver(ReconciliationAlertEvent event) {
        ReconciliationAlert alert = alertRepository.findByEventKey(event.eventKey())
                .orElseGet(() -> alertRepository.save(ReconciliationAlert.pending(event)));

        if (alert.getDeliveryStatus() != ReconciliationAlertStatus.PENDING) {
            metrics.recordAlertDuplicate();
            log.info("[ReconciliationAlert] 중복 또는 예약된 알림 생략: eventKey={}, status={}",
                    event.eventKey(), alert.getDeliveryStatus());
            return;
        }

        alert.startDelivery();
        send(alert, event);
    }

    @Transactional
    public List<Long> claimRetryBatch() {
        LocalDateTime now = LocalDateTime.now();
        int recovered = alertRepository.resetStaleProcessing(
                ReconciliationAlertStatus.PROCESSING,
                ReconciliationAlertStatus.FAILED,
                now.minusMinutes(staleAfterMinutes), now,
                "알림 처리 제한 시간을 초과하여 재시도 대상으로 복구"
        );
        if (recovered > 0) {
            metrics.recordAlertStaleRecovered(recovered);
        }

        List<ReconciliationAlert> alerts = alertRepository.findRetryCandidatesForUpdate(
                ReconciliationAlertStatus.FAILED, now, PageRequest.of(0, batchSize));
        alerts.forEach(ReconciliationAlert::startDelivery);
        return alerts.stream().map(ReconciliationAlert::getId).toList();
    }

    @Transactional
    public void deliverClaimed(Long alertId) {
        ReconciliationAlert alert = alertRepository.findById(alertId)
                .orElseThrow(() -> new IllegalArgumentException("대사 알림을 찾을 수 없습니다: " + alertId));
        if (alert.getDeliveryStatus() != ReconciliationAlertStatus.PROCESSING) {
            log.info("[ReconciliationAlert] 이미 처리된 재시도 생략: alertId={}, status={}",
                    alertId, alert.getDeliveryStatus());
            return;
        }
        send(alert, alert.toEvent());
    }

    private void send(ReconciliationAlert alert, ReconciliationAlertEvent event) {
        try {
            alertSender.send(event);
            alert.markSent();
            metrics.recordAlertDelivery("success");
        } catch (Exception e) {
            metrics.recordAlertDelivery("failure");
            if (alert.getDeliveryAttempts() >= maxAttempts) {
                alert.markExhausted(e.getMessage());
                metrics.recordAlertRetry("exhausted");
                log.error("[ReconciliationAlert] 최대 재시도 초과: eventKey={}, attempts={}",
                        event.eventKey(), alert.getDeliveryAttempts(), e);
                return;
            }

            Duration delay = retryDelay(alert.getDeliveryAttempts());
            alert.markFailed(e.getMessage(), LocalDateTime.now().plus(delay));
            metrics.recordAlertRetry("scheduled");
            log.warn("[ReconciliationAlert] 재시도 예약: eventKey={}, attempts={}, delay={}",
                    event.eventKey(), alert.getDeliveryAttempts(), delay, e);
        }
    }

    private Duration retryDelay(int attempts) {
        long multiplier = 1L << Math.min(Math.max(attempts - 1, 0), 20);
        return Duration.ofSeconds(retryBaseSeconds).multipliedBy(multiplier);
    }
}

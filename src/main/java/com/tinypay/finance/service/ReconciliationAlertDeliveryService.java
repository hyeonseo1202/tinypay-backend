package com.tinypay.finance.service;

import com.tinypay.finance.domain.ReconciliationAlert;
import com.tinypay.finance.domain.ReconciliationAlertStatus;
import com.tinypay.finance.event.ReconciliationAlertEvent;
import com.tinypay.finance.repository.ReconciliationAlertRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReconciliationAlertDeliveryService {

    private final ReconciliationAlertRepository alertRepository;
    private final ReconciliationAlertSender alertSender;
    private final PaymentReconciliationMetrics metrics;

    @Transactional
    public void deliver(ReconciliationAlertEvent event) {
        ReconciliationAlert alert = alertRepository.findByEventKey(event.eventKey())
                .orElseGet(() -> alertRepository.save(ReconciliationAlert.pending(
                        event.eventKey(), event.paymentId(), event.status(), event.attempt())));

        if (alert.getDeliveryStatus() == ReconciliationAlertStatus.SENT) {
            metrics.recordAlertDuplicate();
            log.info("[ReconciliationAlert] 중복 알림 생략: eventKey={}", event.eventKey());
            return;
        }

        alert.retry();
        try {
            alertSender.send(event);
            alert.markSent();
            metrics.recordAlertDelivery("success");
        } catch (Exception e) {
            alert.markFailed(e.getMessage());
            metrics.recordAlertDelivery("failure");
            log.error("[ReconciliationAlert] 알림 발송 실패: eventKey={}", event.eventKey(), e);
        }
    }
}

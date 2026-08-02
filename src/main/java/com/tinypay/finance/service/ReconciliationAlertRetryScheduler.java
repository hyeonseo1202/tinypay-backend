package com.tinypay.finance.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "payment.reconciliation.alert.retry-enabled",
        havingValue = "true"
)
public class ReconciliationAlertRetryScheduler {

    private final ReconciliationAlertDeliveryService deliveryService;

    @Scheduled(
            fixedDelayString = "${payment.reconciliation.alert.retry-fixed-delay-ms:60000}",
            initialDelayString = "${payment.reconciliation.alert.retry-initial-delay-ms:30000}"
    )
    public void retryFailedAlerts() {
        List<Long> alertIds = deliveryService.claimRetryBatch();
        if (alertIds.isEmpty()) {
            return;
        }

        log.info("[ReconciliationAlert] 재시도 배치 시작: count={}", alertIds.size());
        for (Long alertId : alertIds) {
            deliveryService.deliverClaimed(alertId);
        }
        log.info("[ReconciliationAlert] 재시도 배치 종료: count={}", alertIds.size());
    }
}

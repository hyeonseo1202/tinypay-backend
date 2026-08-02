package com.tinypay.finance.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "payment.reconciliation.outbox.enabled", havingValue = "true")
public class ReconciliationAlertOutboxScheduler {
    private final ReconciliationAlertOutboxService outboxService;

    @Scheduled(fixedDelayString = "${payment.reconciliation.outbox.fixed-delay-ms:5000}",
            initialDelayString = "${payment.reconciliation.outbox.initial-delay-ms:5000}")
    public void publishEvents() {
        for (Long outboxId : outboxService.claimBatch()) {
            outboxService.publishOne(outboxId);
        }
    }
}

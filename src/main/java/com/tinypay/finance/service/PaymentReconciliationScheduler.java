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
        name = "payment.reconciliation.enabled",
        havingValue = "true"
)
public class PaymentReconciliationScheduler {

    private final PaymentReconciliationService paymentReconciliationService;
    private final PaymentReconciliationMetrics metrics;

    @Scheduled(
            fixedDelayString = "${payment.reconciliation.fixed-delay-ms:60000}",
            initialDelayString = "${payment.reconciliation.initial-delay-ms:30000}"
    )
    public void reconcilePayments() {
        try {
            List<Long> paymentIds = paymentReconciliationService.claimBatch();
            if (paymentIds.isEmpty()) {
                return;
            }

            log.info("[PaymentReconciliation] 대사 배치 시작: count={}", paymentIds.size());
            for (Long paymentId : paymentIds) {
                paymentReconciliationService.reconcileOne(paymentId);
            }
            log.info("[PaymentReconciliation] 대사 배치 종료: count={}", paymentIds.size());
        } finally {
            metrics.refreshStatusGauges();
        }
    }
}

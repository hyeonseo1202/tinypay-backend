package com.tinypay.finance.service;

import com.tinypay.finance.event.ReconciliationAlertEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class ReconciliationAlertEventListener {

    private final ReconciliationAlertDeliveryService deliveryService;

    @Async("reconciliationAlertExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(ReconciliationAlertEvent event) {
        deliveryService.deliver(event);
    }
}

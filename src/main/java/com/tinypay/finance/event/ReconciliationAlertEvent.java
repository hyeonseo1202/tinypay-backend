package com.tinypay.finance.event;

import com.tinypay.finance.domain.ReconciliationStatus;
import java.math.BigDecimal;

public record ReconciliationAlertEvent(Long paymentId, String txHash, BigDecimal amount,
                                       ReconciliationStatus status, int attempt, String detail) {
    public String eventKey() {
        return paymentId + ":" + status.name() + ":" + attempt;
    }
}

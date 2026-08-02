package com.tinypay.finance.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record ReconciliationListResponse(
        List<ReconciliationItem> payments,
        int page,
        boolean hasNext
) {
    public record ReconciliationItem(
            Long paymentId,
            Long requestId,
            String transactionHash,
            BigDecimal amount,
            String paymentStatus,
            String reconciliationStatus,
            int attempts,
            LocalDateTime lastReconciledAt,
            String error
    ) {
    }
}

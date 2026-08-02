package com.tinypay.finance.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record ReconciliationHistoryResponse(
        Long paymentId,
        List<VerificationItem> verifications
) {
    public record VerificationItem(
            Long verificationId,
            String status,
            Integer failedAtStep,
            BigDecimal expectedAmount,
            BigDecimal actualAmount,
            String expectedReceiver,
            String actualReceiver,
            String detail,
            LocalDateTime createdAt
    ) {
    }
}

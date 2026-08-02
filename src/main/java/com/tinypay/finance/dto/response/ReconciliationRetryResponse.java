package com.tinypay.finance.dto.response;

public record ReconciliationRetryResponse(
        Long paymentId,
        String reconciliationStatus
) {
}

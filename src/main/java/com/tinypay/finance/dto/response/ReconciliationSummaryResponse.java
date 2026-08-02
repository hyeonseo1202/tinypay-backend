package com.tinypay.finance.dto.response;

public record ReconciliationSummaryResponse(
        long pending,
        long processing,
        long matched,
        long mismatched,
        long retryRequired,
        long retryExhausted
) {
}

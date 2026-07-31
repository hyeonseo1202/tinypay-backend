package com.tinypay.finance.domain;

public enum ReconciliationStatus {
    PENDING,
    PROCESSING,
    MATCHED,
    MISMATCHED,
    RETRY_REQUIRED,
    RETRY_EXHAUSTED
}

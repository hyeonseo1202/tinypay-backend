package com.tinypay.finance.domain;

public enum ReconciliationOutboxStatus {
    PENDING,
    PROCESSING,
    PUBLISHED,
    FAILED,
    EXHAUSTED
}

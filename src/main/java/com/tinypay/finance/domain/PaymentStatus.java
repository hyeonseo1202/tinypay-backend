package com.tinypay.finance.domain;

import java.util.Set;

public enum PaymentStatus {
    REQUESTED,
    APPROVED,
    PAID,
    VERIFIED,
    COMPLETED,
    FAILED,

    /**
     * 상태 머신 도입 전 완료 결제 데이터와의 호환성을 위한 상태.
     * 신규 결제에서는 사용하지 않는다.
     */
    @Deprecated
    SUCCESS;

    public boolean isSuccessful() {
        return this == COMPLETED || this == SUCCESS;
    }

    public static Set<PaymentStatus> successfulStatuses() {
        return Set.of(COMPLETED, SUCCESS);
    }

    public static Set<PaymentStatus> budgetCommittedStatuses() {
        return Set.of(APPROVED, PAID, VERIFIED, COMPLETED, SUCCESS);
    }
}

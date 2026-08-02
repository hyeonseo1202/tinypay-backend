package com.tinypay.finance.service;

import com.tinypay.finance.domain.PaymentLog;
import com.tinypay.finance.domain.ReconciliationStatus;
import com.tinypay.finance.dto.response.*;
import com.tinypay.finance.repository.PaymentLogRepository;
import com.tinypay.finance.repository.TxVerificationLogRepository;
import com.tinypay.global.exception.CustomException;
import com.tinypay.global.exception.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PaymentReconciliationMonitoringService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final List<ReconciliationStatus> DEFAULT_ALERT_STATUSES = List.of(
            ReconciliationStatus.MISMATCHED,
            ReconciliationStatus.RETRY_EXHAUSTED
    );

    private final PaymentLogRepository paymentLogRepository;
    private final TxVerificationLogRepository txVerificationLogRepository;
    private final PaymentReconciliationMetrics metrics;

    @Transactional(readOnly = true)
    public ReconciliationSummaryResponse getSummary() {
        return new ReconciliationSummaryResponse(
                paymentLogRepository.countByReconciliationStatus(ReconciliationStatus.PENDING)
                        + paymentLogRepository.countByReconciliationStatusIsNull(),
                paymentLogRepository.countByReconciliationStatus(ReconciliationStatus.PROCESSING),
                paymentLogRepository.countByReconciliationStatus(ReconciliationStatus.MATCHED),
                paymentLogRepository.countByReconciliationStatus(ReconciliationStatus.MISMATCHED),
                paymentLogRepository.countByReconciliationStatus(ReconciliationStatus.RETRY_REQUIRED),
                paymentLogRepository.countByReconciliationStatus(ReconciliationStatus.RETRY_EXHAUSTED)
        );
    }

    @Transactional(readOnly = true)
    public ReconciliationListResponse getAlerts(
            Collection<ReconciliationStatus> statuses,
            int page,
            int size
    ) {
        Collection<ReconciliationStatus> requestedStatuses =
                statuses == null || statuses.isEmpty() ? DEFAULT_ALERT_STATUSES : statuses;
        Page<PaymentLog> result = paymentLogRepository.findByReconciliationStatusInOrderByIdDesc(
                requestedStatuses,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE))
        );
        return new ReconciliationListResponse(
                result.getContent().stream().map(this::toItem).toList(),
                result.getNumber(),
                result.hasNext()
        );
    }

    @Transactional(readOnly = true)
    public ReconciliationHistoryResponse getHistory(Long paymentId) {
        if (!paymentLogRepository.existsById(paymentId)) {
            throw new CustomException(ErrorType.PAYMENT_NOT_FOUND);
        }
        return new ReconciliationHistoryResponse(
                paymentId,
                txVerificationLogRepository.findByPayment_IdOrderByIdDesc(paymentId).stream()
                        .map(log -> new ReconciliationHistoryResponse.VerificationItem(
                                log.getId(),
                                log.getVerificationStatus().name(),
                                log.getFailedAtStep(),
                                log.getExpectedAmount(),
                                log.getActualAmount(),
                                log.getExpectedReceiver(),
                                log.getActualReceiver(),
                                log.getDetail(),
                                log.getCreatedAt()
                        )).toList()
        );
    }

    @Transactional
    public ReconciliationRetryResponse requestRetry(Long paymentId) {
        PaymentLog payment = paymentLogRepository.findById(paymentId)
                .orElseThrow(() -> new CustomException(ErrorType.PAYMENT_NOT_FOUND));
        try {
            payment.requestManualReconciliation();
        } catch (IllegalStateException e) {
            throw new CustomException(ErrorType.INVALID_RECONCILIATION_STATUS, e.getMessage());
        }
        metrics.recordManualRetry();
        return new ReconciliationRetryResponse(paymentId, payment.getReconciliationStatus().name());
    }

    private ReconciliationListResponse.ReconciliationItem toItem(PaymentLog payment) {
        return new ReconciliationListResponse.ReconciliationItem(
                payment.getId(),
                payment.getRequest().getId(),
                payment.getTxHash(),
                payment.getAmount(),
                payment.getPaymentStatus().name(),
                payment.getReconciliationStatus() == null
                        ? ReconciliationStatus.PENDING.name()
                        : payment.getReconciliationStatus().name(),
                payment.getReconciliationAttempts(),
                payment.getLastReconciledAt(),
                payment.getReconciliationError()
        );
    }
}

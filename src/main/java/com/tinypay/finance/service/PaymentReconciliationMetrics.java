package com.tinypay.finance.service;

import com.tinypay.finance.domain.ReconciliationStatus;
import com.tinypay.finance.repository.PaymentLogRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class PaymentReconciliationMetrics {

    private static final String RESULT_METRIC = "tinypay.payment.reconciliation.results";
    private static final String STATUS_METRIC = "tinypay.payment.reconciliation.status";

    private final PaymentLogRepository paymentLogRepository;
    private final MeterRegistry meterRegistry;
    private final Map<ReconciliationStatus, AtomicLong> statusGauges =
            new EnumMap<>(ReconciliationStatus.class);

    public PaymentReconciliationMetrics(
            PaymentLogRepository paymentLogRepository,
            MeterRegistry meterRegistry
    ) {
        this.paymentLogRepository = paymentLogRepository;
        this.meterRegistry = meterRegistry;
        for (ReconciliationStatus status : ReconciliationStatus.values()) {
            AtomicLong value = new AtomicLong();
            statusGauges.put(status, value);
            meterRegistry.gauge(STATUS_METRIC, Tags.of("status", status.name()), value);
        }
    }

    public void record(ReconciliationStatus status) {
        Counter.builder(RESULT_METRIC)
                .tag("result", status.name())
                .register(meterRegistry)
                .increment();
    }

    public void recordManualRetry() {
        Counter.builder("tinypay.payment.reconciliation.manual_retry")
                .register(meterRegistry)
                .increment();
    }

    public void refreshStatusGauges() {
        for (ReconciliationStatus status : ReconciliationStatus.values()) {
            long count = paymentLogRepository.countByReconciliationStatus(status);
            if (status == ReconciliationStatus.PENDING) {
                count += paymentLogRepository.countByReconciliationStatusIsNull();
            }
            statusGauges.get(status).set(count);
        }
    }
}

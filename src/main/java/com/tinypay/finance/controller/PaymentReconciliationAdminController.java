package com.tinypay.finance.controller;

import com.tinypay.finance.domain.ReconciliationStatus;
import com.tinypay.finance.dto.response.*;
import com.tinypay.finance.service.PaymentReconciliationMonitoringService;
import com.tinypay.finance.service.ReconciliationOpsAuthorizer;
import com.tinypay.global.response.ApiResponse;
import com.tinypay.global.response.SuccessType;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/reconciliation")
@RequiredArgsConstructor
public class PaymentReconciliationAdminController {

    private final PaymentReconciliationMonitoringService monitoringService;
    private final ReconciliationOpsAuthorizer opsAuthorizer;

    @GetMapping("/summary")
    public ApiResponse<ReconciliationSummaryResponse> getSummary(
            @RequestHeader(value = "X-Ops-Key", required = false) String opsKey
    ) {
        opsAuthorizer.authorize(opsKey);
        return ApiResponse.success(SuccessType.PROCESS_SUCCESS, monitoringService.getSummary());
    }

    @GetMapping("/alerts")
    public ApiResponse<ReconciliationListResponse> getAlerts(
            @RequestHeader(value = "X-Ops-Key", required = false) String opsKey,
            @RequestParam(required = false) List<ReconciliationStatus> statuses,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        opsAuthorizer.authorize(opsKey);
        return ApiResponse.success(
                SuccessType.PROCESS_SUCCESS,
                monitoringService.getAlerts(statuses, page, size)
        );
    }

    @GetMapping("/{paymentId}/history")
    public ApiResponse<ReconciliationHistoryResponse> getHistory(
            @RequestHeader(value = "X-Ops-Key", required = false) String opsKey,
            @PathVariable Long paymentId
    ) {
        opsAuthorizer.authorize(opsKey);
        return ApiResponse.success(SuccessType.PROCESS_SUCCESS, monitoringService.getHistory(paymentId));
    }

    @PostMapping("/{paymentId}/retry")
    public ApiResponse<ReconciliationRetryResponse> retry(
            @RequestHeader(value = "X-Ops-Key", required = false) String opsKey,
            @PathVariable Long paymentId
    ) {
        opsAuthorizer.authorize(opsKey);
        return ApiResponse.success(SuccessType.PROCESS_SUCCESS, monitoringService.requestRetry(paymentId));
    }
}

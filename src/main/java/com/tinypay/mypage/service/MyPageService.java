package com.tinypay.mypage.service;

import com.tinypay.finance.domain.BudgetPolicy;
import com.tinypay.finance.domain.PaymentLog;
import com.tinypay.finance.domain.PaymentStatus;
import com.tinypay.finance.domain.Wallet;
import com.tinypay.finance.repository.BudgetPolicyRepository;
import com.tinypay.finance.repository.PaymentLogRepository;
import com.tinypay.finance.repository.WalletRepository;
import com.tinypay.global.exception.CustomException;
import com.tinypay.global.exception.ErrorType;
import com.tinypay.mypage.dto.response.MyPageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
@RequiredArgsConstructor
public class MyPageService {

    private final WalletRepository walletRepository;
    private final BudgetPolicyRepository budgetPolicyRepository;
    private final PaymentLogRepository paymentLogRepository;

    @Transactional(readOnly = true)
    public MyPageResponse getMyPage(Long userId) {
        Wallet wallet = walletRepository.findByUser_Id(userId).orElse(null);

        BudgetPolicy policy = budgetPolicyRepository.findByUser_IdAndDeletedAtIsNull(userId)
                .orElse(null);

        BigDecimal usedAmount = paymentLogRepository
                .sumSuccessfulAmountThisMonth(userId, PaymentStatus.successfulStatuses());
        BigDecimal limitAmount = policy != null ? policy.getMonthlyLimit() : null;
        BigDecimal usageRate = calculateUsageRate(usedAmount, limitAmount);

        List<PaymentLog> recentLogs = paymentLogRepository
                .findTop3ByUser_IdAndPaymentStatusInOrderByExecutedAtDesc(
                        userId,
                        PaymentStatus.successfulStatuses()
                );

        long transactionCount = paymentLogRepository
                .countSuccessfulThisMonth(userId, PaymentStatus.successfulStatuses());
        BigDecimal averageAmount = paymentLogRepository
                .averageSuccessfulAmountThisMonth(userId, PaymentStatus.successfulStatuses());

        return MyPageResponse.builder()
                .balance(wallet != null ? wallet.getBalance() : null)
                .monthlyBudget(MyPageResponse.MonthlyBudget.builder()
                        .usedAmount(usedAmount)
                        .limitAmount(limitAmount)
                        .usageRate(usageRate)
                        .build())
                .perPaymentLimit(policy != null ? policy.getPerRequestLimit() : null)
                .recentPayments(recentLogs.stream()
                        .map(log -> MyPageResponse.RecentPayment.builder()
                                .serviceName(log.getRequest().getServiceName())
                                .paidAmount(log.getAmount())
                                .executedAt(log.getExecutedAt())
                                .build())
                        .toList())
                .monthlyTransactionSummary(MyPageResponse.MonthlyTransactionSummary.builder()
                        .transactionCount(transactionCount)
                        .averageTransactionAmount(averageAmount)
                        .build())
                .build();
    }

    private BigDecimal calculateUsageRate(BigDecimal usedAmount, BigDecimal limitAmount) {
        if (limitAmount == null || limitAmount.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return usedAmount.divide(limitAmount, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(1, RoundingMode.HALF_UP);
    }
}

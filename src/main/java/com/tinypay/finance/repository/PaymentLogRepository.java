package com.tinypay.finance.repository;

import com.tinypay.dify.domain.AiRequest;
import com.tinypay.finance.domain.PaymentLog;
import com.tinypay.finance.domain.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PaymentLogRepository extends JpaRepository<PaymentLog, Long> {

    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM PaymentLog p " +
            "WHERE p.user.id = :userId " +
            "AND p.paymentStatus IN :statuses " +
            "AND YEAR(p.executedAt) = YEAR(CURRENT_DATE) " +
            "AND MONTH(p.executedAt) = MONTH(CURRENT_DATE)")
    BigDecimal sumSuccessfulAmountThisMonth(
            @Param("userId") Long userId,
            @Param("statuses") Collection<PaymentStatus> statuses
    );

    Optional<PaymentLog> findByRequest(AiRequest request);

    Optional<PaymentLog> findByRequestAndPaymentStatus(AiRequest request, PaymentStatus status);

    Optional<PaymentLog> findFirstByRequestAndPaymentStatusIn(
            AiRequest request,
            Collection<PaymentStatus> statuses
    );

    void deleteByRequestAndPaymentStatus(AiRequest request, PaymentStatus status);

    List<PaymentLog> findTop3ByUser_IdAndPaymentStatusInOrderByExecutedAtDesc(
            Long userId,
            Collection<PaymentStatus> statuses
    );

    List<PaymentLog> findTop10ByUser_IdOrderByIdDesc(Long userId);

    List<PaymentLog> findTop10ByUser_IdAndIdLessThanOrderByIdDesc(Long userId, Long cursor);

    @Query("SELECT COUNT(p) FROM PaymentLog p " +
            "WHERE p.user.id = :userId " +
            "AND p.paymentStatus IN :statuses " +
            "AND YEAR(p.executedAt) = YEAR(CURRENT_DATE) " +
            "AND MONTH(p.executedAt) = MONTH(CURRENT_DATE)")
    long countSuccessfulThisMonth(
            @Param("userId") Long userId,
            @Param("statuses") Collection<PaymentStatus> statuses
    );

    @Query("SELECT COALESCE(AVG(p.amount), 0) FROM PaymentLog p " +
            "WHERE p.user.id = :userId " +
            "AND p.paymentStatus IN :statuses " +
            "AND YEAR(p.executedAt) = YEAR(CURRENT_DATE) " +
            "AND MONTH(p.executedAt) = MONTH(CURRENT_DATE)")
    BigDecimal averageSuccessfulAmountThisMonth(
            @Param("userId") Long userId,
            @Param("statuses") Collection<PaymentStatus> statuses
    );
}

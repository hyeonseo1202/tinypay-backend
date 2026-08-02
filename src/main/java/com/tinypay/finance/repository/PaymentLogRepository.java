package com.tinypay.finance.repository;

import com.tinypay.dify.domain.AiRequest;
import com.tinypay.finance.domain.PaymentLog;
import com.tinypay.finance.domain.PaymentStatus;
import com.tinypay.finance.domain.ReconciliationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;

import jakarta.persistence.LockModeType;

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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT p
            FROM PaymentLog p
            WHERE p.txHash IS NOT NULL
              AND p.paymentStatus IN :paymentStatuses
              AND (
                    p.reconciliationStatus IS NULL
                    OR p.reconciliationStatus IN :reconciliationStatuses
                  )
              AND (
                    p.nextReconciliationAt IS NULL
                    OR p.nextReconciliationAt <= :now
                  )
            ORDER BY p.id
            """)
    List<PaymentLog> findReconciliationCandidatesForUpdate(
            @Param("paymentStatuses") Collection<PaymentStatus> paymentStatuses,
            @Param("reconciliationStatuses") Collection<com.tinypay.finance.domain.ReconciliationStatus> reconciliationStatuses,
            @Param("now") java.time.LocalDateTime now,
            Pageable pageable
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE PaymentLog p
               SET p.reconciliationStatus = :retryStatus,
                   p.nextReconciliationAt = :now,
                   p.reconciliationError = :error
             WHERE p.reconciliationStatus = :processingStatus
               AND p.reconciliationStartedAt < :staleBefore
            """)
    int resetStaleReconciliations(
            @Param("processingStatus") com.tinypay.finance.domain.ReconciliationStatus processingStatus,
            @Param("retryStatus") com.tinypay.finance.domain.ReconciliationStatus retryStatus,
            @Param("staleBefore") java.time.LocalDateTime staleBefore,
            @Param("now") java.time.LocalDateTime now,
            @Param("error") String error
    );

    long countByReconciliationStatus(ReconciliationStatus status);

    long countByReconciliationStatusIsNull();

    Page<PaymentLog> findByReconciliationStatusInOrderByIdDesc(
            Collection<ReconciliationStatus> statuses,
            Pageable pageable
    );
}

package com.tinypay.finance.repository;

import com.tinypay.finance.domain.ReconciliationAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import com.tinypay.finance.domain.ReconciliationAlertStatus;
import java.time.LocalDateTime;
import java.util.List;

import java.util.Optional;

public interface ReconciliationAlertRepository extends JpaRepository<ReconciliationAlert, Long> {
    Optional<ReconciliationAlert> findByEventKey(String eventKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT a FROM ReconciliationAlert a
            WHERE a.deliveryStatus = :status
              AND a.nextRetryAt <= :now
            ORDER BY a.id
            """)
    List<ReconciliationAlert> findRetryCandidatesForUpdate(
            @Param("status") ReconciliationAlertStatus status,
            @Param("now") LocalDateTime now,
            Pageable pageable
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE ReconciliationAlert a
               SET a.deliveryStatus = :failedStatus,
                   a.processingStartedAt = null,
                   a.nextRetryAt = :now,
                   a.lastError = :error
             WHERE a.deliveryStatus = :processingStatus
               AND a.processingStartedAt < :staleBefore
            """)
    int resetStaleProcessing(
            @Param("processingStatus") ReconciliationAlertStatus processingStatus,
            @Param("failedStatus") ReconciliationAlertStatus failedStatus,
            @Param("staleBefore") LocalDateTime staleBefore,
            @Param("now") LocalDateTime now,
            @Param("error") String error
    );
}

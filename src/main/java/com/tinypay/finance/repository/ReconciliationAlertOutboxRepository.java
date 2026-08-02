package com.tinypay.finance.repository;

import com.tinypay.finance.domain.ReconciliationAlertOutbox;
import com.tinypay.finance.domain.ReconciliationOutboxStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ReconciliationAlertOutboxRepository extends JpaRepository<ReconciliationAlertOutbox, Long> {
    Optional<ReconciliationAlertOutbox> findByEventKey(String eventKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT o FROM ReconciliationAlertOutbox o
            WHERE o.publishStatus IN :statuses
              AND (o.nextRetryAt IS NULL OR o.nextRetryAt <= :now)
            ORDER BY o.id
            """)
    List<ReconciliationAlertOutbox> findPublishCandidatesForUpdate(
            @Param("statuses") Collection<ReconciliationOutboxStatus> statuses,
            @Param("now") LocalDateTime now,
            Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE ReconciliationAlertOutbox o
               SET o.publishStatus = :failedStatus,
                   o.processingStartedAt = null,
                   o.nextRetryAt = :now,
                   o.lastError = :error
             WHERE o.publishStatus = :processingStatus
               AND o.processingStartedAt < :staleBefore
            """)
    int resetStaleProcessing(
            @Param("processingStatus") ReconciliationOutboxStatus processingStatus,
            @Param("failedStatus") ReconciliationOutboxStatus failedStatus,
            @Param("staleBefore") LocalDateTime staleBefore,
            @Param("now") LocalDateTime now,
            @Param("error") String error);
}

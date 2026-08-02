package com.tinypay.finance.repository;

import com.tinypay.finance.domain.ReconciliationAlert;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReconciliationAlertRepository extends JpaRepository<ReconciliationAlert, Long> {
    Optional<ReconciliationAlert> findByEventKey(String eventKey);
}

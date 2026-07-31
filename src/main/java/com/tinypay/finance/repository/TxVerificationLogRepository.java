package com.tinypay.finance.repository;

import com.tinypay.finance.domain.TxVerificationLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TxVerificationLogRepository extends JpaRepository<TxVerificationLog, Long> {
}

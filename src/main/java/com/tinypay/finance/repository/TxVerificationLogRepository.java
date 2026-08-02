package com.tinypay.finance.repository;

import com.tinypay.finance.domain.TxVerificationLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TxVerificationLogRepository extends JpaRepository<TxVerificationLog, Long> {

    List<TxVerificationLog> findByPayment_IdOrderByIdDesc(Long paymentId);
}

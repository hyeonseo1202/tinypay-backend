package com.tinypay.finance.domain;

import com.tinypay.global.common.entity.BaseTimeEntity;
import com.tinypay.user.domain.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Getter
@Entity
@Table(name = "tx_verification_log")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TxVerificationLog extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false)
    private PaymentLog payment;

    @Column(name = "tx_hash", nullable = false)
    private String txHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false)
    private TxVerificationStatus verificationStatus;

    @Column(name = "failed_at_step")
    private Integer failedAtStep;

    @Column(name = "expected_amount", nullable = false, precision = 18, scale = 6)
    private BigDecimal expectedAmount;

    @Column(name = "actual_amount", precision = 18, scale = 6)
    private BigDecimal actualAmount;

    @Column(name = "expected_receiver", nullable = false)
    private String expectedReceiver;

    @Column(name = "actual_receiver")
    private String actualReceiver;

    @Column(name = "token_address")
    private String tokenAddress;

    @Column(name = "is_official_token")
    private Boolean isOfficialToken;

    @Column(name = "detail", length = 1000)
    private String detail;

    @Column(name = "blockchain_network", nullable = false)
    private String blockchainNetwork;

    @Builder
    public TxVerificationLog(User user, PaymentLog payment, String txHash, TxVerificationStatus verificationStatus, Integer failedAtStep, BigDecimal expectedAmount, BigDecimal actualAmount, String expectedReceiver, String actualReceiver, String tokenAddress, Boolean isOfficialToken, String detail, String blockchainNetwork
    ) {
        this.user = user;
        this.payment = payment;
        this.txHash = txHash;
        this.verificationStatus = verificationStatus == null ? TxVerificationStatus.FAILED_TX : verificationStatus;
        this.failedAtStep = failedAtStep;
        this.expectedAmount = expectedAmount;
        this.actualAmount = actualAmount;
        this.expectedReceiver = expectedReceiver;
        this.actualReceiver = actualReceiver;
        this.tokenAddress = tokenAddress;
        this.isOfficialToken = isOfficialToken;
        this.detail = detail;
        this.blockchainNetwork = blockchainNetwork == null ? "POLYGON_AMOY" : blockchainNetwork;
    }
}

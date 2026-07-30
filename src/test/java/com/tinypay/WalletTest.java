package com.tinypay.finance.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WalletTest {

    @Test
    void 잔액보다_큰_금액은_차감할_수_없다() {
        Wallet wallet = walletWithBalance("50.000000");

        assertThat(wallet.canWithdraw(new BigDecimal("50.000001"))).isFalse();
        assertThatThrownBy(() -> wallet.withdraw(new BigDecimal("50.000001")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(wallet.getBalance()).isEqualByComparingTo("50.000000");
    }

    @Test
    void 출금과_충전은_현재_잔액을_기준으로_계산한다() {
        Wallet wallet = walletWithBalance("100.000000");

        wallet.withdraw(new BigDecimal("30.000000"));
        wallet.deposit(new BigDecimal("20.000000"));

        assertThat(wallet.getBalance()).isEqualByComparingTo("90.000000");
    }

    private Wallet walletWithBalance(String balance) {
        return Wallet.builder()
                .walletAddress("0x-wallet")
                .privateKeyEncrypted("encrypted-key")
                .balance(new BigDecimal(balance))
                .walletStatus(WalletStatus.ACTIVE)
                .build();
    }
}

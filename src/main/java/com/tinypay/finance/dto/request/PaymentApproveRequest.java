package com.tinypay.finance.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Getter
@NoArgsConstructor
public class PaymentApproveRequest {

    @NotBlank(message = "멱등성 키가 존재하지 않습니다.")
    @Size(max = 100, message = "멱등성 키는 100자 이하여야 합니다.")
    private String idempotencyKey;

    @NotNull(message = "예상 금액이 존재하지 않습니다.")
    @DecimalMin(value = "0.0", inclusive = false, message = "예상 금액은 0보다 커야합니다.")
    private BigDecimal estimatedCost;

    @Pattern(regexp = "^[0-9]{6}$", message = "지갑 비밀번호는 6자리 숫자여야 합니다.")
    private String walletPassword;
}

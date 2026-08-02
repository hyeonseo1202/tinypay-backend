package com.tinypay.finance.service;

import com.tinypay.global.exception.CustomException;
import com.tinypay.global.exception.ErrorType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReconciliationOpsAuthorizerTest {

    @Test
    void 올바른_운영키는_접근을_허용한다() {
        new ReconciliationOpsAuthorizer("secret-key").authorize("secret-key");
    }

    @Test
    void 잘못된_운영키는_접근을_거부한다() {
        ReconciliationOpsAuthorizer authorizer = new ReconciliationOpsAuthorizer("secret-key");

        assertThatThrownBy(() -> authorizer.authorize("wrong-key"))
                .isInstanceOfSatisfying(CustomException.class,
                        exception -> assertThat(exception.getErrorType())
                                .isEqualTo(ErrorType.OPS_ACCESS_FORBIDDEN));
    }

    @Test
    void 서버에_운영키가_없어도_접근을_거부한다() {
        assertThatThrownBy(() -> new ReconciliationOpsAuthorizer("").authorize("any-key"))
                .isInstanceOf(CustomException.class);
    }
}

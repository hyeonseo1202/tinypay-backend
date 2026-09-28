package com.tinypay.chat.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CreateChatMessageRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void contentAtMaximumLengthIsValid() {
        CreateChatMessageRequest request = new CreateChatMessageRequest(
                "a".repeat(CreateChatMessageRequest.MAX_CONTENT_LENGTH), null
        );

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void contentOverMaximumLengthIsRejected() {
        CreateChatMessageRequest request = new CreateChatMessageRequest(
                "a".repeat(CreateChatMessageRequest.MAX_CONTENT_LENGTH + 1), null
        );

        assertThat(validator.validate(request))
                .singleElement()
                .satisfies(violation -> assertThat(violation.getPropertyPath().toString())
                        .isEqualTo("content"));
    }
}

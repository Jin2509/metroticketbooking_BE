package com.metroticket_api.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

/**
 * Mật khẩu mới: tối thiểu 8 ký tự, tối đa 72 byte UTF-8. BCrypt chỉ nhận 72 byte (ném lỗi nếu dài hơn),
 * nên đếm theo ký tự là chưa đủ: 72 ký tự tiếng Việt có dấu có thể tới 216 byte. Giá trị null được bỏ qua.
 */
@Target({ ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT })
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ValidPassword.Validator.class)
public @interface ValidPassword {

    String message() default "Mật khẩu từ 8 ký tự, tối đa 72 byte";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<ValidPassword, String> {

        private static final int MIN_LENGTH = 8;
        private static final int MAX_BYTES = 72;

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return value == null || (!value.isBlank()
                    && value.codePointCount(0, value.length()) >= MIN_LENGTH
                    && value.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES);
        }
    }
}

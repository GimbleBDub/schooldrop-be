package com.gimble.bus_stop.dto.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Passwords people choose: 8–72 characters (BCrypt ignores anything past 72 bytes) with an uppercase
 * letter, a lowercase letter, a number and a symbol. The app shows the same checklist while typing.
 */
@Documented
@Constraint(validatedBy = {})
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@ReportAsSingleViolation
@NotBlank
@Size(min = 8, max = 72)
@Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9]).*$")
public @interface StrongPassword {

    String message() default "must be 8+ characters with an uppercase letter, a lowercase letter, a number and a symbol";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}

package angstromio.validation.constraints;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintTarget;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * A type of [jakarta.validation.Constraint] used to annotate data class methods
 * to invoke for additional instance state validation.
 */
@Documented
@Constraint(validatedBy = {})
@Target({METHOD, ANNOTATION_TYPE})
@Retention(RUNTIME)
public @interface PostConstructValidation {

    /**
     * Every constraint annotation must define a message element of type String.
     *
     * @return String message.
     */
    String message() default "";

    /**
     * Every constraint annotation must define a groups element that specifies the processing groups
     * with which the constraint declaration is associated.
     *
     * @return Array of processing groups.
     */
    Class<?>[] groups() default {};

    /**
     * Constraint annotations must define a payload element that specifies the payload with which
     * the constraint declaration is associated.
     *
     * @return associated Payload.
     */
    Class<? extends Payload>[] payload() default {};

    /**
     * Specify the fields validated by the method. Used in error reporting.
     *
     * @return Array of fields for reporting.
     */
    String[] fields() default {};
}
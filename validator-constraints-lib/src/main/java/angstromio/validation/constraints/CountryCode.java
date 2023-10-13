package angstromio.validation.constraints;

import java.lang.annotation.Documented;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import angstromio.validation.constraints.CountryCode.List;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.CONSTRUCTOR;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE_USE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Defines a constraint which enforces that the annotated field is a valid ISO-3166 country code.
 * Validated by the {@code ISO3166CountryCodeConstraintValidator} class.
 */
@Documented
@Constraint(validatedBy = {ISO3166CountryCodeConstraintValidator.class})
@Target({METHOD, FIELD, ANNOTATION_TYPE, CONSTRUCTOR, PARAMETER, TYPE_USE})
@Retention(RUNTIME)
@Repeatable(List.class)
public @interface CountryCode {

    /**
     * Every constraint annotation must define a message element of type String.
     *
     * @return String message.
     */
    String message() default "{angstromio.validation.constraints.CountryCode.message}";

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
     * Defines several {@code @CountryCode} annotations on the same element.
     */
    @Target({METHOD, FIELD, ANNOTATION_TYPE, CONSTRUCTOR, PARAMETER, TYPE_USE})
    @Retention(RUNTIME)
    @Documented
    public @interface List {
        /**
         * @return Array of {@code CountryCode} values.
         */
        CountryCode[] value();
    }
}
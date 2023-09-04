package angstromio.validation.constraints;

import java.lang.annotation.Documented;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import angstromio.validation.constraints.OneOf.List;

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
 * Defines a constraint which enforces the annotated field is "one of" the defined values.
 * Validated by the {@code OneOfConstraintValidator} class.
 */
@Documented
@Constraint(validatedBy = {OneOfConstraintValidator.class})
@Target({METHOD, FIELD, ANNOTATION_TYPE, CONSTRUCTOR, PARAMETER, TYPE_USE})
@Retention(RUNTIME)
@Repeatable(List.class)
public @interface OneOf {

    /**
     * Every constraint annotation must define a message element of type String.
     *
     * @return String message
     */
    String message() default "{angstromio.validation.constraints.OneOf.message}";

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
     * The array of valid values.
     *
     * @return an Array of valid values.
     */
    String[] value();

    /**
     * Defines several {@code @OneOf} annotations on the same element.
     */
    @Target({METHOD, FIELD, ANNOTATION_TYPE, CONSTRUCTOR, PARAMETER, TYPE_USE})
    @Retention(RUNTIME)
    @Documented
    public @interface List {
        /**
         * @return an Array of {@code OneOf} values.
         */
        OneOf[] value();
    }
}

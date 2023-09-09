package angstromio.validation.constraints;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.*;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

@Documented
@Constraint(validatedBy = {})
@Target({METHOD, FIELD, ANNOTATION_TYPE, CONSTRUCTOR, PARAMETER, TYPE_USE})
@Retention(RUNTIME)
@Repeatable(ValidPassengerCountReturnValue.List.class)
public @interface ValidPassengerCountReturnValue {

  String message() default "number of passenger(s) is not valid";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};

  /**
   * The max number of passengers.
   */
  long max();

  /**
   * Defines several {@code @OneOf} annotations on the same element.
   */
  @Target({METHOD, FIELD, ANNOTATION_TYPE, CONSTRUCTOR, PARAMETER, TYPE_USE})
  @Retention(RUNTIME)
  @Documented
  public @interface List {
    ValidPassengerCountReturnValue[] value();
  }
}

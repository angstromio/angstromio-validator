package angstromio.validation.constraints;

import java.lang.annotation.Documented;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.CONSTRUCTOR;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

@Documented
@Constraint(validatedBy = {})
@Target({METHOD, FIELD, ANNOTATION_TYPE, CONSTRUCTOR, PARAMETER, TYPE})
@Retention(RUNTIME)
@Repeatable(ValidPassengerCount.List.class)
public @interface ValidPassengerCount {

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
  @Target({METHOD, FIELD, ANNOTATION_TYPE, CONSTRUCTOR, PARAMETER, TYPE})
  @Retention(RUNTIME)
  @Documented
  public @interface List {
    ValidPassengerCount[] value();
  }
}

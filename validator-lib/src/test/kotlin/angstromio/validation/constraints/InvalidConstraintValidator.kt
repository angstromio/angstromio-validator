package angstromio.validation.constraints

import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext

class InvalidConstraintValidator : ConstraintValidator<InvalidConstraint, Any> {
    override fun isValid(value: Any?, context: ConstraintValidatorContext?): Boolean {
        throw RuntimeException("FORCED TEST EXCEPTION")
    }
}
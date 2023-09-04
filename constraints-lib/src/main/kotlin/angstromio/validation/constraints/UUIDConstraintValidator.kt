package angstromio.validation.constraints

import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext

class UUIDConstraintValidator : ConstraintValidator<UUID, String> {

    override fun initialize(constraintAnnotation: UUID?) {
        super.initialize(constraintAnnotation)
    }

    override fun isValid(value: String?, context: ConstraintValidatorContext?): Boolean {
        return if (value == null) {
            true
        } else {
            try {
                java.util.UUID.fromString(value); true
            } catch (e: Exception) {
                false
            }
        }
    }
}
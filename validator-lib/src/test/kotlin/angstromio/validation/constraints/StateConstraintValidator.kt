package angstromio.validation.constraints

import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import org.hibernate.validator.constraintvalidation.HibernateConstraintValidatorContext

class StateConstraintValidator : ConstraintValidator<StateConstraint, String> {
    override fun isValid(value: String?, context: ConstraintValidatorContext?): Boolean {
        val valid = value?.lowercase().equals("CA".lowercase())

        if (!valid) {
            val hibernateContext = context?.unwrap(HibernateConstraintValidatorContext::class.java)
            hibernateContext?.withDynamicPayload(StateConstraintPayload(value!!, "CA"))
        }

        return valid
    }
}
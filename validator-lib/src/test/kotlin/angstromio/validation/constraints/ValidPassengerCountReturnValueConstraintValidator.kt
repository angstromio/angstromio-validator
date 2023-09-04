package angstromio.validation.constraints

import angstromio.validation.testclasses
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.constraintvalidation.SupportedValidationTarget
import jakarta.validation.constraintvalidation.ValidationTarget

@SupportedValidationTarget(value = [ValidationTarget.ANNOTATED_ELEMENT])
class ValidPassengerCountReturnValueConstraintValidator : ConstraintValidator<ValidPassengerCountReturnValue, testclasses.CarWithPassengerCount> {

    @Volatile private var maxPassengers: Long? = null

    override fun initialize(constraintAnnotation: ValidPassengerCountReturnValue?) {
        super.initialize(constraintAnnotation)
        this.maxPassengers = constraintAnnotation!!.max
    }

    override fun isValid(value: testclasses.CarWithPassengerCount?, context: ConstraintValidatorContext?): Boolean {
        return if (value == null) true
        else value.passengers.isNotEmpty() && value.passengers.size <= this.maxPassengers!!
    }
}
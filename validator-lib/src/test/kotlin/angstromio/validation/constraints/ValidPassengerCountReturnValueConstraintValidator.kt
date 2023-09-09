package angstromio.validation.constraints

import angstromio.validation.TestClasses
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.constraintvalidation.SupportedValidationTarget
import jakarta.validation.constraintvalidation.ValidationTarget

@SupportedValidationTarget(value = [ValidationTarget.ANNOTATED_ELEMENT])
class ValidPassengerCountReturnValueConstraintValidator : ConstraintValidator<ValidPassengerCountReturnValue, TestClasses.CarWithPassengerCount> {

    @Volatile private var maxPassengers: Long? = null

    override fun initialize(constraintAnnotation: ValidPassengerCountReturnValue?) {
        super.initialize(constraintAnnotation)
        this.maxPassengers = constraintAnnotation!!.max
    }

    override fun isValid(value: TestClasses.CarWithPassengerCount?, context: ConstraintValidatorContext?): Boolean {
        return if (value == null) true
        else value.passengers.isNotEmpty() && value.passengers.size <= this.maxPassengers!!
    }
}
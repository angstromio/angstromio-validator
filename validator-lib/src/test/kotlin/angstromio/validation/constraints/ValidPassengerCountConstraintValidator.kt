package angstromio.validation.constraints

import angstromio.validation.testclasses
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext

class ValidPassengerCountConstraintValidator : ConstraintValidator<ValidPassengerCount, testclasses.Car> {

    @Volatile private var maxPassengers: Long? = null

    override fun initialize(constraintAnnotation: ValidPassengerCount?) {
        super.initialize(constraintAnnotation)
        this.maxPassengers = constraintAnnotation!!.max
    }

    override fun isValid(value: testclasses.Car?, context: ConstraintValidatorContext?): Boolean {
        return if (value == null) true
        else value.passengers.isNotEmpty() && value.passengers.size <= this.maxPassengers!!
    }
}
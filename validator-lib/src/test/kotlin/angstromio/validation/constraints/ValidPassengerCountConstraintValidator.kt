package angstromio.validation.constraints

import angstromio.validation.TestClasses
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext

class ValidPassengerCountConstraintValidator : ConstraintValidator<ValidPassengerCount, TestClasses.Car> {

    @Volatile private var maxPassengers: Long? = null

    override fun initialize(constraintAnnotation: ValidPassengerCount?) {
        super.initialize(constraintAnnotation)
        this.maxPassengers = constraintAnnotation!!.max
    }

    override fun isValid(value: TestClasses.Car?, context: ConstraintValidatorContext?): Boolean {
        return if (value == null) true
        else value.passengers.isNotEmpty() && value.passengers.size <= this.maxPassengers!!
    }
}
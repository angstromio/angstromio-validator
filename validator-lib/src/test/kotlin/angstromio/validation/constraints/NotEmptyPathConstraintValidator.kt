@file:kotlin.jvm.JvmMultifileClass

package angstromio.validation.constraints

import angstromio.validation.testclasses.TestPath
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.constraints.NotEmpty

class NotEmptyPathConstraintValidator : ConstraintValidator<NotEmpty, TestPath> {
    override fun isValid(value: TestPath?, context: ConstraintValidatorContext?): Boolean =
        when {
            value == TestPath.Empty -> false
            else -> true
        }
}

class NotEmptyAnyConstraintValidator : ConstraintValidator<NotEmpty, Any> {
    override fun isValid(value: Any?, context: ConstraintValidatorContext?): Boolean =
        when {
            value == TestPath.Empty -> false
            else -> true
        }
}
package angstromio.validation.constraints

import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.constraintvalidation.SupportedValidationTarget
import jakarta.validation.constraintvalidation.ValidationTarget
import java.time.LocalDate

@SupportedValidationTarget(value = [ValidationTarget.PARAMETERS])
class ConsistentDateParametersValidator : ConstraintValidator<ConsistentDateParameters, Array<Any?>?> {

    override fun isValid(value: Array<Any?>?, context: ConstraintValidatorContext?): Boolean {
        return when (value) {
            null -> true
            else ->
                when {
                    value.size != 2 || value[0] == null || value[1] == null ->
                        throw IllegalArgumentException("Unexpected method signature")

                    isNotLocalData(value[0]!!.javaClass) || isNotLocalData(value[1]!!.javaClass) ->
                        throw IllegalArgumentException("Unexpected method signature")

                    else ->
                        asLocalData(value[0]!!).isBefore(asLocalData(value[1]!!))
                }
        }
    }

    private fun isLocalDate(clazz: Class<Any>): Boolean = LocalDate::class.java.isAssignableFrom(clazz)
    private fun isNotLocalData(clazz: Class<Any>): Boolean = !isLocalDate(clazz)
    private fun asLocalData(a: Any): LocalDate = a as LocalDate
}
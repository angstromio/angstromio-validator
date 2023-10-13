package angstromio.validation.constraints

import arrow.core.toNonEmptyListOrNull
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.ValidationException

internal class OneOfConstraintValidator : ConstraintValidator<OneOf, Any> {

    @Volatile
    private var oneOf: OneOf? = null
    @Volatile
    private var values: Set<String>? = emptySet()

    override fun initialize(constraintAnnotation: OneOf?) {
        super.initialize(constraintAnnotation)
        this.oneOf = constraintAnnotation
        this.values = constraintAnnotation?.value?.toSet()
    }

    override fun isValid(value: Any?, context: ConstraintValidatorContext?): Boolean {
        return when (value) {
            null -> true
            else -> {
                when (value) {
                    is Array<*> ->
                        validationResult(value.toList(), this.values!!, context)

                    is Map<*, *> ->
                        throw ValidationException("Map type is not supported for fields validated by ${OneOf::class.java.name}")

                    is Collection<*> ->
                        validationResult(value.toList(), this.values!!, context)

                    else ->
                        validationResult(listOf(value), this.values!!, context)
                }
            }
        }
    }

    private fun validationResult(
        value: Iterable<*>,
        oneOfValues: Set<String>,
        constraintValidatorContext: ConstraintValidatorContext?
    ): Boolean {
        val invalidValues = findInvalidValues(value, oneOfValues)
        // an empty value is not one of the given values
        val valid = if (value.toNonEmptyListOrNull() == null) false else invalidValues.isEmpty()
        if (!valid) {
            ConstraintValidatorContextBuilder.addExpressionVariable("validatedValue", valueToString(value.toList()))
                .withMessageTemplate(oneOf!!.message)
                .addConstraintViolation(constraintValidatorContext)
        }
        return valid
    }

    private fun valueToString(value: List<*>): String {
        val valueString = value.joinToString()
        return valueString.ifEmpty {
            "<empty>"
        }
    }

    private fun findInvalidValues(
        value: Iterable<*>,
        oneOfValues: Set<String>
    ): List<String> = value.map { it.toString() }.asSequence().minus(oneOfValues).toList()
}
package angstromio.validation.constraints

import angstromio.validation.ConstraintValidatorContextBuilder
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.ValidationException
import java.util.*

class ISO3166CountryCodeConstraintValidator : ConstraintValidator<CountryCode, Any> {
    @Volatile
    private var countryCode: CountryCode? = null

    companion object {
        /** @see [[https://www.iso.org/iso-3166-country-codes.html ISO 3166]] */
        val CountryCodes: Set<String> = Locale.getISOCountries().toSet()
    }

    override fun initialize(constraintAnnotation: CountryCode?) {
        super.initialize(constraintAnnotation)
        this.countryCode = constraintAnnotation
    }

    override fun isValid(value: Any?, context: ConstraintValidatorContext?): Boolean {
        return when (value) {
            null -> true
            else -> {
                when (value) {
                    is Array<*> ->
                        validationResult(value.toList(), context)

                    is Map<*, *> ->
                        throw ValidationException("Map type is not supported for fields validated by ${CountryCode::class.java.name}")

                    is Collection<*> ->
                        validationResult(value.toList(), context)

                    else ->
                        validationResult(listOf(value), context)
                }
            }
        }
    }

    private fun validationResult(
        value: Iterable<*>,
        constraintValidatorContext: ConstraintValidatorContext?
    ): Boolean {
        val invalidCountryCodes = findInvalidCountryCodes(value)
        val valueAsList = value.toList()
        // an empty value is not a valid country code
        val valid = if (valueAsList.isEmpty()) false else invalidCountryCodes.isEmpty()
        if (!valid) {
            ConstraintValidatorContextBuilder.addExpressionVariable("validatedValue", valueToString(valueAsList))
                .withMessageTemplate(countryCode!!.message)
                .addConstraintViolation(constraintValidatorContext)
        }
        return valid
    }

    private fun valueToString(value: List<*>): String {
        return if (value.isEmpty()) {
            "<empty>"
        } else {
            value.joinToString()
        }
    }

    private fun findInvalidCountryCodes(values: Iterable<*>): List<String> =
        values.toSet().map { it.toString().uppercase() }.asSequence().minus(CountryCodes).toList()
}
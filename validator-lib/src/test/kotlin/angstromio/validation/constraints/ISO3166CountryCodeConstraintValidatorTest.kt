package angstromio.validation.constraints

import angstromio.validation.testclasses.CountryCodeArrayExample
import angstromio.validation.testclasses.CountryCodeExample
import angstromio.validation.testclasses.CountryCodeInvalidTypeExample
import angstromio.validation.testclasses.CountryCodeListExample
import angstromio.validation.DataClassValidator
import io.kotest.matchers.be
import io.kotest.matchers.equals.shouldBeEqual
import io.kotest.matchers.should
import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.az
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.filterNot
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.shuffle
import io.kotest.property.arbitrary.string
import io.kotest.property.forAll
import jakarta.validation.ConstraintViolation
import jakarta.validation.ValidationException
import org.junit.jupiter.api.assertThrows
import java.util.*

class ISO3166CountryCodeConstraintValidatorTest : ConstraintValidatorTest() {
    override val validator: DataClassValidator = DataClassValidator()
    override val testFieldName: String = "countryCode"

    private val countryCodes = Locale.getISOCountries().filter { it.isNotEmpty() }

    init {
        test("pass validation for valid country code") {
            countryCodes.forEach { value ->
                validate<CountryCodeExample>(value).isEmpty() should be(true)
            }
        }

        test("not fail for null value") {
            validate<CountryCodeExample>(null).isEmpty() should be(true)
        }

        test("fail validation for invalid country code") {
            forAll(genFakeCountryCode()) { value ->
                val violations = validate<CountryCodeExample>(value)
                violations.size shouldBeEqual 1
                val violation = violations.first()
                violation.message == "$value not a valid country code" && violation.propertyPath.toString() == testFieldName
            }
        }

        test("pass validation for valid country codes in list") {
            val passValue = Arb.shuffle(countryCodes)

            forAll(passValue) { value ->
                validate<CountryCodeListExample>(value).isEmpty()
            }
        }

        test("fail validation for empty list") {
            val violations = validate<CountryCodeListExample>(emptyList<String>())
            violations.size shouldBeEqual 1
            val violation = violations.first()
            violation.message should be("<empty> not a valid country code")
            violation.propertyPath.toString() should be(testFieldName)
        }

        test("fail validation for invalid country codes in list") {
            val failValue = Arb.list(genFakeCountryCode()).filter { it.isNotEmpty() }

            forAll(failValue) { value ->
                val violations = validate<CountryCodeArrayExample>(value)
                violations.size shouldBeEqual 1
                val violation = violations.first()
                violation.message == "${value.joinToString()} not a valid country code" && violation.propertyPath.toString() == testFieldName
            }
        }

        test("fail validation for invalid country code type") {
            val failValue = Arb.int(0, 100)

            forAll(failValue) { value ->
                val violations = validate<CountryCodeInvalidTypeExample>(value)
                violations.size shouldBeEqual 1
                val violation = violations.first()
                violation.message == "$value not a valid country code" && violation.propertyPath.toString() == testFieldName
            }
        }

        test("fail for map type") {
            assertThrows<ValidationException> {
                validate<CountryCodeListExample>(emptyMap<String, String>())
            }
        }
    }

    // generate random uppercase string for fake country code
    private fun genFakeCountryCode(): Arb<String> {
        return Arb.string(2, Codepoint.az())
            .map { it.uppercase() }
            .filter { it.isNotEmpty() }
            .filterNot { a -> countryCodes.contains(a) }
    }

    private inline fun <reified T : Any> validate(value: Any?): Set<ConstraintViolation<T>> =
        super.validate<CountryCode, T>(T::class, testFieldName, value)
}
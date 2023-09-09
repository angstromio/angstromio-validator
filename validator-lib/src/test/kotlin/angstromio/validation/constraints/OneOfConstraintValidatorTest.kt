package angstromio.validation.constraints

import angstromio.validation.DataClassValidator
import angstromio.validation.TestClasses.OneOfExample
import angstromio.validation.TestClasses.OneOfInvalidTypeExample
import angstromio.validation.TestClasses.OneOfListExample
import io.kotest.matchers.be
import io.kotest.matchers.equals.shouldBeEqual
import io.kotest.matchers.should
import io.kotest.property.Arb
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.shuffle
import io.kotest.property.arbitrary.string
import io.kotest.property.forAll
import jakarta.validation.ConstraintViolation
import jakarta.validation.ValidationException
import org.junit.jupiter.api.assertThrows

class OneOfConstraintValidatorTest : ConstraintValidatorTest() {
    override val validator: DataClassValidator = DataClassValidator()
    override val testFieldName: String = "enumValue"

    private val oneOfValues: Set<String> = setOf("a", "B", "c")

    init {

        test("pass validation for single value") {
            oneOfValues.forEach { value ->
                validate<OneOfExample>(value).isEmpty() should be(true)
            }
        }

        test("not fail for null value") {
            validate<OneOfExample>(null).isEmpty() should be(true)
        }

        test("fail validation for single value") {
            val failValue = Arb.string(1, 1).filter { str ->
                !oneOfValues.contains(str)
            }

            forAll(failValue) { value ->
                val violations = validate<OneOfExample>(value)
                violations.size shouldBeEqual 1
                val violation = violations.first()
                (violation.message.endsWith("not one of [a, B, c]")) && (violation.propertyPath.toString() == testFieldName)
            }
        }

        test("pass validation for list") {
            val passValue = Arb.shuffle(oneOfValues.toList())

            forAll(passValue) { value ->
                val violations = validate<OneOfListExample>(value)
                violations.isEmpty()
            }
        }

        test("fail validation for empty list") {
            val emptyList = emptyList<String>()

            val violations = validate<OneOfListExample>(emptyList)
            violations.size shouldBeEqual 1
            violations.first().message.endsWith("not one of [a, B, c]") should be(true)
            violations.first().propertyPath.toString() should be(testFieldName)
        }

        test("fail validation for invalid value in list") {
            val failValue = Arb.list(
                Arb.string(1, 1).filter { str ->
                    !oneOfValues.contains(str)
                }, 1..3
            )

            forAll(failValue) { value ->
                val violations = validate<OneOfListExample>(value)
                violations.size shouldBeEqual 1
                val violation = violations.first()
                violation.message.endsWith("not one of [a, B, c]") && violation.propertyPath.toString() == testFieldName
            }
        }

        test("fail validation for invalid type") {
            val failValue = Arb.int(0, 100)

            forAll(failValue) { value ->
                val violations = validate<OneOfInvalidTypeExample>(value)
                violations.size shouldBeEqual 1
                val violation = violations.first()
                violation.message.endsWith("not one of [a, B, c]") && violation.propertyPath.toString() == testFieldName
            }
        }

        test("fail for map type") {
            assertThrows<ValidationException> {
                validate<OneOfListExample>(emptyMap<String, String>())
            }
        }
    }

    private inline fun <reified T : Any> validate(value: Any?): Set<ConstraintViolation<T>> =
        super.validate<OneOf, T>(T::class.java, testFieldName, value)
}
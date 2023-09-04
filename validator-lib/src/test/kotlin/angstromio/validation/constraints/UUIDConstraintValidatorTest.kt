package angstromio.validation.constraints

import angstromio.validation.DataClassValidator
import angstromio.validation.testclasses.UUIDExample
import io.kotest.matchers.equals.shouldBeEqual
import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.alphanumeric
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.string
import io.kotest.property.arbitrary.uuid
import io.kotest.property.forAll
import jakarta.validation.ConstraintViolation

class UUIDConstraintValidatorTest : ConstraintValidatorTest() {
    override val validator: DataClassValidator = DataClassValidator()
    override val testFieldName: String = "uuid"

    init {
        test("pass validation for valid uuid") {
            val passValue = Arb.uuid()

            forAll(passValue) { value ->
                validate<UUIDExample>(value.toString()).isEmpty()
            }
        }

        test("fail validation for invalid uuid") {
            val passValue = Arb.string(36, Codepoint.alphanumeric()).filter { isNotUUID(it) }

            forAll(passValue) { value ->
                val violations = validate<UUIDExample>(value)
                violations.size shouldBeEqual 1
                val violation = violations.first()
                violation.message == "must be a valid UUID" && violation.propertyPath.toString() == testFieldName
            }
        }
    }

    private inline fun <reified T : Any> validate(value: Any?): Set<ConstraintViolation<T>> =
        super.validate<UUID, T>(T::class, testFieldName, value)

    private fun isNotUUID(value: String): Boolean {
        return try {
            java.util.UUID.fromString(value)
            false
        } catch (e: Exception) {
            true
        }
    }
}
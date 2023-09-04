package angstromio.validation.engine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.be
import io.kotest.matchers.nulls.beNull
import io.kotest.matchers.should
import io.kotest.matchers.shouldNot
import jakarta.validation.Payload
import org.junit.jupiter.api.assertThrows

class MethodValidationResultTest : FunSpec({
    test("MethodValidationResult#validIfTrue - thrown NonFatal exception will return Invalid") {
        val payload = object : Payload {}
        val result = MethodValidationResult.validIfTrue(
            { throw RuntimeException("FORCED TEST EXCEPTION") },
            { "Oh Noes!" },
            payload
        )
        result.isValid() should be(false)
        result.payload() shouldNot beNull()
        result.payload() should be(payload)
        (result as MethodValidationResult.Invalid).message should be("FORCED TEST EXCEPTION")
    }

    test("MethodValidationResult#validIfTrue - thrown Fatal exception will escape") {
        assertThrows<OutOfMemoryError> {
            MethodValidationResult.validIfTrue(
                { throw OutOfMemoryError("FORCED TEST EXCEPTION") },
                { "Oh Noes!" },
                object : Payload {}
            )
        }
    }

    test("MethodValidationResult#validIfTrue - returns Valid") {
        val result = MethodValidationResult.validIfTrue(
            { true },
            { "This is it!" },
            object : Payload {}
        )
        result should be(MethodValidationResult.Valid)
        result.isValid() should be(true)
        result.payload() should beNull() // Valid results have no payload
    }

    test("MethodValidationResult#validIfTrue - returns Invalid") {
        val payload = object : Payload {}
        val expected = MethodValidationResult.Invalid("This is not it!", payload)

        val result = MethodValidationResult.validIfTrue(
            { false },
            { "This is not it!" },
            payload
        )
        result should be(expected)
        result.isValid() should be(false)
        result.payload() shouldNot beNull()
        result.payload() should be(expected.payload)
        (result as MethodValidationResult.Invalid).message should be(expected.message)
    }

    test("MethodValidationResult#validIfFalse - thrown NonFatal exception will return Valid") {
        val payload = object : Payload {}
        val result = MethodValidationResult.validIfFalse(
            { throw RuntimeException("FORCED TEST EXCEPTION") },
            { "Oh Noes!" },
            payload
        )
        result should be(MethodValidationResult.Valid)
        result.isValid() should be(true)
        result.payload() should beNull() // Valid results have no payload
    }

    test("MethodValidationResult#validIfFalse - thrown Fatal exception will escape") {
        assertThrows<OutOfMemoryError> {
            MethodValidationResult.validIfFalse(
                { throw OutOfMemoryError("FORCED TEST EXCEPTION") },
                { "Oh Noes!" },
                object : Payload {}
            )
        }
    }

    test("MethodValidationResult#validIfFalse - returns Valid") {
        val result = MethodValidationResult.validIfFalse(
            { false },
            { "This is correct!" },
            object : Payload {}
        )
        result should be(MethodValidationResult.Valid)
        result.isValid() should be(true)
        result.payload() should beNull()// Valid results have no payload
    }

    test("MethodValidationResult#validIfFalse - returns Invalid") {
        val payload = object : Payload {}
        val expected = MethodValidationResult.Invalid("This is not correct!", payload)

        val result = MethodValidationResult.validIfFalse(
            { true },
            { "This is not correct!" },
            payload
        )
        result should be(expected)
        result.isValid() should be(false)
        result.payload() shouldNot beNull()
        result.payload() should be(expected.payload)
        (result as MethodValidationResult.Invalid).message should be(expected.message)
    }
})
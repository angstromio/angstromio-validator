package angstromio.validation.engine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.be
import io.kotest.matchers.nulls.beNull
import io.kotest.matchers.should
import io.kotest.matchers.shouldNot
import jakarta.validation.Payload
import org.junit.jupiter.api.assertThrows

class PostConstructValidationResultTest : FunSpec({
    test("PostConstructValidationResult#validIfTrue - thrown NonFatal exception will return Invalid") {
        val payload = object : Payload {}
        val result = PostConstructValidationResult.validIfTrue(
            { throw RuntimeException("FORCED TEST EXCEPTION") },
            { "Oh Noes!" },
            payload
        )
        result.isValid() should be(false)
        result.payload() shouldNot beNull()
        result.payload() should be(payload)
        (result as PostConstructValidationResult.Invalid).message should be("FORCED TEST EXCEPTION")
    }

    test("PostConstructValidationResult#validIfTrue - thrown Fatal exception will escape") {
        assertThrows<OutOfMemoryError> {
            PostConstructValidationResult.validIfTrue(
                { throw OutOfMemoryError("FORCED TEST EXCEPTION") },
                { "Oh Noes!" },
                object : Payload {}
            )
        }
    }

    test("PostConstructValidationResult#validIfTrue - returns Valid") {
        val result = PostConstructValidationResult.validIfTrue(
            { true },
            { "This is it!" },
            object : Payload {}
        )
        result should be(PostConstructValidationResult.Valid)
        result.isValid() should be(true)
        result.payload() should beNull() // Valid results have no payload
    }

    test("PostConstructValidationResult#validIfTrue - returns Invalid") {
        val payload = object : Payload {}
        val expected = PostConstructValidationResult.Invalid("This is not it!", payload)

        val result = PostConstructValidationResult.validIfTrue(
            { false },
            { "This is not it!" },
            payload
        )
        result should be(expected)
        result.isValid() should be(false)
        result.payload() shouldNot beNull()
        result.payload() should be(expected.payload)
        (result as PostConstructValidationResult.Invalid).message should be(expected.message)
    }

    test("PostConstructValidationResult#validIfFalse - thrown NonFatal exception will return Valid") {
        val payload = object : Payload {}
        val result = PostConstructValidationResult.validIfFalse(
            { throw RuntimeException("FORCED TEST EXCEPTION") },
            { "Oh Noes!" },
            payload
        )
        result should be(PostConstructValidationResult.Valid)
        result.isValid() should be(true)
        result.payload() should beNull() // Valid results have no payload
    }

    test("PostConstructValidationResult#validIfFalse - thrown Fatal exception will escape") {
        assertThrows<OutOfMemoryError> {
            PostConstructValidationResult.validIfFalse(
                { throw OutOfMemoryError("FORCED TEST EXCEPTION") },
                { "Oh Noes!" },
                object : Payload {}
            )
        }
    }

    test("PostConstructValidationResult#validIfFalse - returns Valid") {
        val result = PostConstructValidationResult.validIfFalse(
            { false },
            { "This is correct!" },
            object : Payload {}
        )
        result should be(PostConstructValidationResult.Valid)
        result.isValid() should be(true)
        result.payload() should beNull()// Valid results have no payload
    }

    test("PostConstructValidationResult#validIfFalse - returns Invalid") {
        val payload = object : Payload {}
        val expected = PostConstructValidationResult.Invalid("This is not correct!", payload)

        val result = PostConstructValidationResult.validIfFalse(
            { true },
            { "This is not correct!" },
            payload
        )
        result should be(expected)
        result.isValid() should be(false)
        result.payload() shouldNot beNull()
        result.payload() should be(expected.payload)
        (result as PostConstructValidationResult.Invalid).message should be(expected.message)
    }
})
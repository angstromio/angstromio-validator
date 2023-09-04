package angstromio.validation.engine

import angstromio.util.control.NonFatal
import jakarta.validation.Payload

sealed interface MethodValidationResult {
    fun payload(): Payload?
    fun isValid(): Boolean

    object EmptyPayload : Payload

    data object Valid : MethodValidationResult {
        override fun payload(): Payload? = null
        override fun isValid(): Boolean = true
    }

    data class Invalid(
        val message: String?,
        val payload: Payload? = null
    ) : MethodValidationResult {
        override fun payload(): Payload? = this.payload
        override fun isValid(): Boolean = false
    }

    companion object {
        fun validIfTrue(
            condition: () -> Boolean,
            message: () -> String,
            payload: Payload? = null
        ): MethodValidationResult {
            return try {
                when (condition.invoke()) {
                    true -> Valid
                    else -> Invalid(message.invoke(), payload)
                }
            } catch (e: Exception) {
                if (!NonFatal.isNonFatal(e)) {
                    throw e
                }
                Invalid(e.message, payload)
            }
        }

        fun validIfFalse(
            condition: () -> Boolean,
            message: () -> String,
            payload: Payload? = null
        ): MethodValidationResult {

            return try {
                when (condition.invoke()) {
                    true -> Invalid(message.invoke(), payload)
                    else -> Valid
                }
            } catch (e: Exception) {
                if (!NonFatal.isNonFatal(e)) {
                    throw e
                }
                Valid
            }
        }
    }
}


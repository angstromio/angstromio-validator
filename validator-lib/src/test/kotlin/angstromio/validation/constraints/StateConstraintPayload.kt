package angstromio.validation.constraints

import jakarta.validation.Payload

data class StateConstraintPayload(val invalid: String, val expected: String) : Payload

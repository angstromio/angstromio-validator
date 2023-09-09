package angstromio.validation.constraints

import angstromio.validation.DataClassValidator
import io.kotest.core.spec.style.FunSpec
import jakarta.validation.ConstraintViolation

abstract class ConstraintValidatorTest : FunSpec() {

    abstract val validator: DataClassValidator

    abstract val testFieldName: String

    protected fun <A : Annotation, T : Any> validate(
        clazz: Class<T>,
        paramName: String,
        value: Any?
    ): Set<ConstraintViolation<T>> = validator.validateValue(clazz, paramName, value)
}
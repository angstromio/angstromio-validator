package angstromio.validation.constraints

import angstromio.validation.DataClassValidator
import io.kotest.core.spec.style.FunSpec
import jakarta.validation.ConstraintViolation
import kotlin.reflect.KClass

abstract class ConstraintValidatorTest : FunSpec() {

    abstract val validator: DataClassValidator

    abstract val testFieldName: String

    protected fun <A : Annotation, T : Any> validate(
        clazz: KClass<T>,
        paramName: String,
        value: Any?
    ): Set<ConstraintViolation<T>> = validator.validateValue(clazz, paramName, value)
}
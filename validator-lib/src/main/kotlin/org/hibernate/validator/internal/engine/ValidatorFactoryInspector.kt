package org.hibernate.validator.internal.engine

import jakarta.validation.ConstraintValidatorFactory
import jakarta.validation.MessageInterpolator
import org.hibernate.validator.internal.metadata.core.ConstraintHelper
import org.hibernate.validator.internal.util.ExecutableHelper
import org.hibernate.validator.internal.util.ExecutableParameterNameProvider
import java.lang.reflect.Field

/** Allows access to the configured [ConstraintHelper] and [ConstraintValidatorFactory] */
class ValidatorFactoryInspector(
    private val underlying: ValidatorFactoryImpl
) {

    fun constraintHelper(): ConstraintHelper =
        underlying.constraintCreationContext.constraintHelper

    fun constraintValidatorFactory(): ConstraintValidatorFactory =
        underlying.constraintValidatorFactory

    fun validatorFactoryScopedContext(): ValidatorFactoryScopedContext =
        underlying.validatorFactoryScopedContext

    fun messageInterpolator(): MessageInterpolator =
        underlying.messageInterpolator

    fun getConstraintCreationContext(): ConstraintCreationContext =
        underlying.constraintCreationContext

    fun getExecutableHelper(): ExecutableHelper {
        val field: Field = ValidatorFactoryImpl::class.java.getDeclaredField("executableHelper")
        field.setAccessible(true)
        return field.get(underlying) as ExecutableHelper
    }

    fun getExecutableParameterNameProvider(): ExecutableParameterNameProvider =
        underlying.executableParameterNameProvider

    fun getMethodValidationConfiguration(): MethodValidationConfiguration =
        underlying.methodValidationConfiguration

    /** Close underlying ValidatorFactoryImpl */
    fun close(): Unit = underlying.close()
}
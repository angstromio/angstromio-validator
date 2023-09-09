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

    val constraintHelper: ConstraintHelper =
        underlying.constraintCreationContext.constraintHelper

    val constraintValidatorFactory: ConstraintValidatorFactory =
        underlying.constraintValidatorFactory

    val validatorFactoryScopedContext: ValidatorFactoryScopedContext =
        underlying.validatorFactoryScopedContext

    val messageInterpolator: MessageInterpolator =
        underlying.messageInterpolator

    val  constraintCreationContext: ConstraintCreationContext =
        underlying.constraintCreationContext

    val executableHelper: ExecutableHelper by lazy {
        val field: Field = ValidatorFactoryImpl::class.java.getDeclaredField("executableHelper")
        field.setAccessible(true)
        field.get(underlying) as ExecutableHelper
    }

    val executableParameterNameProvider: ExecutableParameterNameProvider =
        underlying.executableParameterNameProvider

    val methodValidationConfiguration: MethodValidationConfiguration =
        underlying.methodValidationConfiguration

    /** Close underlying ValidatorFactoryImpl */
    fun close(): Unit = underlying.close()
}
package angstromio.validation.internal.constraintvalidation

import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.metadata.ConstraintDescriptor
import org.hibernate.validator.internal.engine.ValidatorFactoryInspector
import org.hibernate.validator.internal.engine.constraintvalidation.ConstraintValidatorContextImpl
import org.hibernate.validator.internal.engine.path.PathImpl

internal class ConstraintValidatorContextFactory(private val validatorFactory: ValidatorFactoryInspector) {

    fun newConstraintValidatorContext(
        path: PathImpl,
        constraintDescriptor: ConstraintDescriptor<*>
    ): ConstraintValidatorContext =
        ConstraintValidatorContextImpl(
        validatorFactory.validatorFactoryScopedContext.clockProvider,
        path,
        constraintDescriptor,
        validatorFactory.validatorFactoryScopedContext.constraintValidatorPayload,
        validatorFactory.validatorFactoryScopedContext.constraintExpressionLanguageFeatureLevel,
        validatorFactory.validatorFactoryScopedContext.customViolationExpressionLanguageFeatureLevel
    )
}
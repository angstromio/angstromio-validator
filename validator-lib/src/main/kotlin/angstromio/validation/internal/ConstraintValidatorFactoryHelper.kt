package angstromio.validation.internal

import arrow.core.memoize
import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.UnexpectedTypeException
import jakarta.validation.ValidationException
import jakarta.validation.constraintvalidation.SupportedValidationTarget
import jakarta.validation.constraintvalidation.ValidationTarget
import org.hibernate.validator.internal.engine.ValidatorFactoryInspector
import org.hibernate.validator.internal.engine.constraintvalidation.ConstraintValidatorManager
import org.hibernate.validator.internal.metadata.descriptor.ConstraintDescriptorImpl

internal object ConstraintValidatorFactoryHelper {

    private val parametersValidationTargetFilter = ::parametersValidationTargetFilterFn.memoize()

    /** @note this method is memoized as it should only ever need to be calculated once for a given [[ConstraintValidator]] */
    private fun parametersValidationTargetFilterFn(constraintValidator: ConstraintValidator<*, *>): Boolean {
        return when (val annotation =
            constraintValidator::class.annotations.find { it.annotationClass == SupportedValidationTarget::class }) {
            null -> false
            else -> (annotation as SupportedValidationTarget).value.contains(ValidationTarget.PARAMETERS)
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun findInitializedConstraintValidator(
        context: ValidationContext<*>,
        validatorFactory: ValidatorFactoryInspector,
        constraintValidatorManager: ConstraintValidatorManager,
        constraintDescriptor: ConstraintDescriptorImpl<Annotation>,
        clazz: Class<*>,
        value: Any?
    ): Set<ConstraintValidator<Annotation, Any>> {
        val validator = findInitializedConstraintValidator(
            validatorFactory = validatorFactory,
            constraintValidatorManager = constraintValidatorManager,
            constraintDescriptor = constraintDescriptor,
            clazz = clazz,
            value = value
        )
        return when (validator) {
            null -> {
                val set = findConstraintValidators(
                    validatorFactory = validatorFactory,
                    annotationClazz = constraintDescriptor.annotation.annotationClass.java
                )
                if (set.isEmpty()) {
                    val configuration = when (context.rootClazz) {
                        null ->
                            context.path.toString().ifEmpty { clazz.simpleName }
                        else ->
                            context.rootClazz.simpleName + "." + context.path.toString().ifEmpty { clazz.simpleName }
                    }
                    throw UnexpectedTypeException(
                        "No validator could be found for constraint '${constraintDescriptor.annotation.annotationClass}'" + " validating type '${clazz.name}'. " + "Check configuration for '$configuration'"
                    )
                }
                // find the first filtered validator
                val filtered = set.filter(parametersValidationTargetFilter)
                    .map { it as ConstraintValidator<Annotation, Any> }
                filtered.forEach { it.initialize(constraintDescriptor.annotation) }
                filtered.toSet()
            }

            else -> setOf(validator)
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun findInitializedConstraintValidator(
        validatorFactory: ValidatorFactoryInspector,
        constraintValidatorManager: ConstraintValidatorManager,
        constraintDescriptor: ConstraintDescriptorImpl<Annotation>,
        clazz: Class<*>,
        value: Any?
    ): ConstraintValidator<Annotation, Any>? {
        return constraintValidatorManager.getInitializedValidator(
            Types.refineAsJavaType(clazz, value),
            constraintDescriptor,
            validatorFactory.constraintValidatorFactory,
            validatorFactory.validatorFactoryScopedContext.constraintValidatorInitializationContext
        )?.let { it as ConstraintValidator<Annotation, Any> }
    }


    fun findConstraintValidators(
        validatorFactory: ValidatorFactoryInspector,
        annotationClazz: (Class<out Annotation>)
    ): Set<ConstraintValidator<*, *>> {
        // compute from constraint, otherwise compute from registry
        val validatedBy = if (annotationClazz.isAnnotationPresent(Constraint::class.java)) {
            val constraintAnnotation = annotationClazz.getAnnotation(Constraint::class.java)
            constraintAnnotation.validatedBy.map { clazz ->
                validatorFactory.constraintValidatorFactory.getInstance(clazz.java) ?: throw ValidationException(
                    "Constraint factory returned null when trying to create instance of $clazz."
                )
            }.toSet()
        } else emptySet()
        return validatedBy.ifEmpty {
            validatorFactory.constraintHelper.getAllValidatorDescriptors(annotationClazz)
                .map { it.newInstance(validatorFactory.constraintValidatorFactory) }.toSet()
        }
    }
}
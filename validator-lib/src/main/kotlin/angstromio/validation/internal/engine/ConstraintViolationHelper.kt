package angstromio.validation.internal.engine

import angstromio.validation.constraints.PostConstructValidation
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.ConstraintViolation
import jakarta.validation.ConstraintViolationException
import jakarta.validation.Payload
import jakarta.validation.metadata.ConstraintDescriptor
import org.hibernate.validator.internal.engine.ConstraintViolationImpl
import org.hibernate.validator.internal.engine.MessageInterpolatorContext
import org.hibernate.validator.internal.engine.ValidatorFactoryInspector
import org.hibernate.validator.internal.engine.constraintvalidation.ConstraintValidatorContextImpl
import org.hibernate.validator.internal.engine.constraintvalidation.ConstraintViolationCreationContext
import org.hibernate.validator.internal.engine.path.PathImpl
import java.util.*

internal class ConstraintViolationHelper(private val validatorFactory: ValidatorFactoryInspector) {

    companion object {
        private const val MESSAGE_WITH_PATH_TEMPLATE = "%s: %s"

        fun <T : Any> sortedSet(unsorted: Collection<ConstraintViolation<T>>): Set<ConstraintViolation<T>> =
            unsorted.toSortedSet { o1, o2 ->
                Objects.requireNonNull(o1)
                Objects.requireNonNull(o2)
                getMessage(o1).compareTo(getMessage(o2))
            }

        fun <T : Any> sorted(unsorted: Collection<ConstraintViolation<T>>): List<ConstraintViolation<T>> =
            unsorted.sortedWith {  o1, o2 ->
                Objects.requireNonNull(o1)
                Objects.requireNonNull(o2)
                getMessage(o1).compareTo(getMessage(o2))
            }

        /* Private */

        fun getMessage(violation: ConstraintViolation<*>): String =
            MESSAGE_WITH_PATH_TEMPLATE.format(violation.propertyPath.toString(), violation.message)
    }

    /** Instantiate a new [ConstraintViolationException] from the given Set of violations */
    fun newConstraintViolationException(
        violations: Set<ConstraintViolation<Any>>
    ): ConstraintViolationException {
        val errors: Set<ConstraintViolation<Any>> = sortedSet(violations)

        val message: String =
            "\nValidation Errors:\t\t" + errors.joinToString(", ") { getMessage(it) } + "\n\n"
        return ConstraintViolationException(message, errors)
    }

    /** Return a ConstraintViolation resulting from a PostConstructValidation failure */
    fun <T: Any> newPostConstructValidationConstraintViolation(
        constraint: PostConstructValidation,
        message: String?,
        path: PathImpl,
        invalidValue: Any?,
        rootClazz: Class<T>?,
        root: T?,
        leaf: Any?,
        constraintDescriptor: ConstraintDescriptor<*>,
        payload: Payload?
    ): ConstraintViolation<T> =
        newConstraintViolation(
            messageTemplate = constraint.message,
            interpolatedMessage = message,
            path = path,
            invalidValue = invalidValue,
            rootClazz = rootClazz,
            root = root,
            leaf = leaf,
            constraintDescriptor = constraintDescriptor,
            payload = payload
        )

    /**
     * Performs message interpolation given the constraint descriptor and constraint validator context
     * to create a set of [[ConstraintViolation]] from the given context and parameters.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> buildConstraintViolations(
        rootClazz: Class<out T>?,
        root: T?,
        leaf: Any?,
        path: PathImpl,
        invalidValue: Any?,
        constraintDescriptor: ConstraintDescriptor<*>,
        constraintValidatorContext: ConstraintValidatorContext
    ): Set<ConstraintViolation<T>> {
        val results = mutableSetOf<ConstraintViolation<T>>()
        val constraintViolationCreationContexts =
            (constraintValidatorContext as ConstraintValidatorContextImpl).getConstraintViolationCreationContexts()

        var index = 0
        val size = constraintViolationCreationContexts.size
        while (index < size) {
            val constraintViolationCreationContext = constraintViolationCreationContexts[index]
            val messageTemplate = constraintViolationCreationContext.message
            val interpolatedMessage =
                validatorFactory.messageInterpolator
                    .interpolate(
                        messageTemplate,
                        MessageInterpolatorContext(
                            constraintDescriptor,
                            invalidValue,
                            root?.javaClass,
                            constraintViolationCreationContext.path,
                            constraintViolationCreationContext.messageParameters,
                            constraintViolationCreationContext.expressionVariables,
                            constraintViolationCreationContext.expressionLanguageFeatureLevel,
                            constraintViolationCreationContext.isCustomViolation
                        )
                    )
            results.add(
                newConstraintViolation<T>(
                    messageTemplate,
                    interpolatedMessage,
                    path,
                    invalidValue,
                    rootClazz as Class<T>,
                    root,
                    leaf,
                    constraintDescriptor,
                    constraintViolationCreationContext
                )
            )
            index += 1
        }

        return results.ifEmpty { emptySet() }
    }

    /** Creates a new [ConstraintViolation] from the given context and parameters. */
    private fun <T : Any> newConstraintViolation(
        messageTemplate: String,
        interpolatedMessage: String?,
        path: PathImpl,
        invalidValue: Any?,
        rootClazz: Class<T>?,
        root: T?,
        leaf: Any?,
        constraintDescriptor: ConstraintDescriptor<*>,
        payload: Payload?
    ): ConstraintViolation<T> =
        ConstraintViolationImpl.forBeanValidation(
            messageTemplate,
            emptyMap(),
            emptyMap(),
            interpolatedMessage,
            rootClazz,
            root,
            leaf,
            invalidValue,
            path,
            constraintDescriptor,
            payload
        )

    /** Creates a new [ConstraintViolation] from the given context and parameters. */
    private fun <T : Any> newConstraintViolation(
        messageTemplate: String,
        interpolatedMessage: String,
        path: PathImpl,
        invalidValue: Any?,
        rootClazz: Class<T>?,
        root: T?,
        leaf: Any?,
        constraintDescriptor: ConstraintDescriptor<*>,
        constraintViolationCreationContext: ConstraintViolationCreationContext
    ): ConstraintViolation<T> =
        ConstraintViolationImpl.forBeanValidation(
            messageTemplate,
            constraintViolationCreationContext.messageParameters,
            constraintViolationCreationContext.expressionVariables,
            interpolatedMessage,
            rootClazz,
            root,
            leaf,
            invalidValue,
            path,
            constraintDescriptor,
            constraintViolationCreationContext.dynamicPayload
        )
}
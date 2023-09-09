package angstromio.validation.internal.metadata.descriptor

import angstromio.validation.constraints.PostConstructValidation
import jakarta.validation.ConstraintTarget
import jakarta.validation.ConstraintValidator
import jakarta.validation.Payload
import jakarta.validation.ValidationException
import jakarta.validation.metadata.ConstraintDescriptor
import jakarta.validation.metadata.ValidateUnwrappedValue
import java.util.*

internal class PostConstructValidationConstraintDescriptor(private val annotation: PostConstructValidation) : ConstraintDescriptor<PostConstructValidation> {

    override fun getAnnotation(): PostConstructValidation = annotation

    override fun getMessageTemplate(): String = annotation.message

    override fun getGroups(): MutableSet<Class<*>> {
        val target = mutableSetOf<Class<*>>()
        annotation.groups.forEach { target.add(it.java) }
        return target
    }

    override fun getPayload(): MutableSet<Class<out Payload>> {
        val target = mutableSetOf<Class<out Payload>>()
        annotation.payload.forEach { target.add(it.java) }
        return target
    }

    override fun getValidationAppliesTo(): ConstraintTarget = ConstraintTarget.PARAMETERS

    override fun getConstraintValidatorClasses(): MutableList<Class<out ConstraintValidator<PostConstructValidation, *>>> = Collections.emptyList()

    override fun getAttributes(): MutableMap<String, Any> {
        val target = mutableMapOf<String, Any>()
        var i = 0
        annotation.fields.forEach { key ->
            target[key] = Integer.valueOf(i)
            i += 1
        }
        return target
    }

    override fun getComposingConstraints(): MutableSet<ConstraintDescriptor<*>> = Collections.emptySet()

    override fun isReportAsSingleViolation(): Boolean = true

    override fun getValueUnwrapping(): ValidateUnwrappedValue = ValidateUnwrappedValue.DEFAULT

    override fun <U : Any?> unwrap(type: Class<U>?): U = throw ValidationException("${type?.name} is unsupported")
}
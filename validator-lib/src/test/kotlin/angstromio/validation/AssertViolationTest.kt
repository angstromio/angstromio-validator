package angstromio.validation

import angstromio.util.extensions.Nulls.whenNotNull
import angstromio.validation.extensions.sorted
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.be
import io.kotest.matchers.equals.shouldBeEqual
import io.kotest.matchers.nulls.beNull
import io.kotest.matchers.should
import io.kotest.matchers.shouldNot
import jakarta.validation.ConstraintViolation
import kotlin.reflect.KClass

abstract class AssertViolationTest : FunSpec() {

    data class WithViolation<T>(
        val path: String,
        val message: String,
        val invalidValue: Any?,
        val rootBeanClazz: Class<T>,
        val root: T,
        val annotation: Annotation? = null
    )

    abstract val validator: DataClassValidator

    fun <T : Any> assertViolations(
        violations: Set<ConstraintViolation<T>>,
        withViolations: List<WithViolation<T>>
    ) {
        violations.size shouldBeEqual withViolations.size
        val sortedViolations: List<ConstraintViolation<T>> = violations.sorted()
        sortedViolations.withIndex().map { it.index to it.value }.map { (index, constraintViolation) ->
            val withViolation = withViolations[index]
            constraintViolation.message shouldBeEqual withViolation.message
            if (constraintViolation.propertyPath == null) withViolation.path should beNull()
            else constraintViolation.propertyPath.toString() shouldBeEqual withViolation.path
            constraintViolation.invalidValue should be(withViolation.invalidValue)
            constraintViolation.rootBeanClass shouldBeEqual withViolation.rootBeanClazz
            withViolation.root.javaClass.name shouldBeEqual constraintViolation.rootBean.javaClass.name
            constraintViolation.leafBean shouldNot beNull()
            withViolation.annotation.whenNotNull { ann ->
                constraintViolation.constraintDescriptor.annotation.annotationClass should be(ann.annotationClass)
            }
        }
    }

    fun <T : Any> assertViolations(
        obj: T,
        groups: List<KClass<*>> = emptyList(),
        withViolations: List<WithViolation<T>> = emptyList()
    ) {
        val violations: Set<ConstraintViolation<T>> = validator.validate(obj, *groups.toTypedArray())
        assertViolations(violations, withViolations)
    }

}
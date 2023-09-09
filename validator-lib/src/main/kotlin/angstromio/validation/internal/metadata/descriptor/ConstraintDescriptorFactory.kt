package angstromio.validation.internal.metadata.descriptor

import jakarta.validation.groups.Default
import org.hibernate.validator.internal.engine.ValidatorFactoryInspector
import org.hibernate.validator.internal.metadata.core.ConstraintOrigin
import org.hibernate.validator.internal.metadata.descriptor.ConstraintDescriptorImpl
import org.hibernate.validator.internal.metadata.location.ConstraintLocation
import org.hibernate.validator.internal.metadata.raw.ConstrainedElement
import org.hibernate.validator.internal.properties.Constrainable
import org.hibernate.validator.internal.util.annotation.ConstraintAnnotationDescriptor
import java.lang.reflect.Type

internal class ConstraintDescriptorFactory(private val validatorFactory: ValidatorFactoryInspector) {

    fun newConstraintDescriptor(
        name: String?,
        clazz: Class<*>,
        declaringClazz: Class<*>,
        annotation: Annotation,
        constrainedElementKind: ConstrainedElement.ConstrainedElementKind = ConstrainedElement.ConstrainedElementKind.FIELD
    ) = ConstraintDescriptorImpl(
        validatorFactory.constraintHelper,
        mkConstrainable(name, clazz, declaringClazz, constrainedElementKind),
        ConstraintAnnotationDescriptor.Builder(annotation).build(),
        ConstraintLocation.ConstraintLocationKind.of(constrainedElementKind),
        Default::class.java,
        ConstraintOrigin.DEFINED_LOCALLY,
        ConstraintDescriptorImpl.ConstraintType.GENERIC
    )

    fun newConstraintDescriptor(
        constrainable: Constrainable,
        annotation: Annotation
    ) = ConstraintDescriptorImpl(
        validatorFactory.constraintHelper,
        constrainable,
        ConstraintAnnotationDescriptor.Builder(annotation).build(),
        ConstraintLocation.ConstraintLocationKind.of(constrainable.constrainedElementKind),
        Default::class.java,
        ConstraintOrigin.DEFINED_LOCALLY,
        ConstraintDescriptorImpl.ConstraintType.GENERIC
    )

    fun mkConstrainable(
        name: String?,
        clazz: Class<*>,
        declaringClazz: Class<*>,
        constrainedElementKind: ConstrainedElement.ConstrainedElementKind = ConstrainedElement.ConstrainedElementKind.FIELD
    ): Constrainable = object : Constrainable {
        override fun getName(): String? = name
        override fun getDeclaringClass(): Class<*> = declaringClazz
        override fun getTypeForValidatorResolution(): Type = clazz
        override fun getType(): Type = clazz
        override fun getConstrainedElementKind(): ConstrainedElement.ConstrainedElementKind = constrainedElementKind
    }
}
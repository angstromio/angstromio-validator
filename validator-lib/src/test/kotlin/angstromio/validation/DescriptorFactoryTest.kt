package angstromio.validation

import angstromio.validation.internal.metadata.descriptor.ConstraintDescriptorFactory
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.be
import io.kotest.matchers.equals.shouldBeEqual
import io.kotest.matchers.nulls.beNull
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNot
import jakarta.validation.Validation
import jakarta.validation.constraints.Future
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Past
import jakarta.validation.spi.ValidationProvider
import org.hibernate.validator.HibernateValidator
import org.hibernate.validator.HibernateValidatorConfiguration
import org.hibernate.validator.internal.engine.ValidatorFactoryImpl
import org.hibernate.validator.internal.engine.ValidatorFactoryInspector
import java.time.LocalDate
import kotlin.reflect.KTypeProjection
import kotlin.reflect.KVariance
import kotlin.reflect.full.createType
import kotlin.reflect.jvm.jvmErasure

@Suppress("UNCHECKED_CAST")
class DescriptorFactoryTest : FunSpec() {
    private val configuration: HibernateValidatorConfiguration =
        Validation
            .byProvider(HibernateValidator::class.java as Class<ValidationProvider<HibernateValidatorConfiguration>>)
            .configure()

    private val validatorFactory = ValidatorFactoryInspector(configuration.buildValidatorFactory() as ValidatorFactoryImpl)
    private val constraintDescriptorFactory: ConstraintDescriptorFactory = ConstraintDescriptorFactory(validatorFactory)

    private val descriptorFactory: DescriptorFactory =
        DescriptorFactory(128L, validatorFactory, constraintDescriptorFactory)

    init {
        test("DescriptorFactory#constructors") {
            val description = descriptorFactory.describe(TestClasses.WithSecondaryConstructor::class.java)
            description shouldNot beNull()
            description.elementClass shouldBeEqual TestClasses.WithSecondaryConstructor::class.java
            description.constrainedConstructors.size shouldBeEqual 2
            description.constrainedConstructors.forEach { constructor ->
                constructor.constraintDescriptors.isEmpty() should be(true) // neither constructor has any constructor annotations
                constructor.parameterDescriptors.size shouldBeEqual 1 // both have one constrained parameter
                constructor.parameterDescriptors.forEach { parameterDescription ->
                    // neither is cascaded
                    parameterDescription.isCascaded should be(false)
                    when (parameterDescription.name) {
                        "four" -> {
                            // there should be a @NotBlank annotation
                            parameterDescription.constraintDescriptors.size shouldBeEqual 1
                            parameterDescription.constraintDescriptors.first().annotation.annotationClass should be(NotBlank::class)
                        }

                        "one" -> {
                            // there should be @Min annotation
                            parameterDescription.constraintDescriptors.size shouldBeEqual 1
                            parameterDescription.constraintDescriptors.first().annotation.annotationClass should be(Min::class)
                        }

                        else -> {
                            parameterDescription.constraintDescriptors.isEmpty() shouldBe true
                        }
                    }
                }
            }
            description.isBeanConstrained should be(true) // has constrained properties
            description.constraintDescriptors.size shouldBeEqual 0 // but class itself has no constraints
            description.constrainedProperties.size shouldBeEqual 1  // one: Int
            val constrainedPropertyForPropertyOne = description.getConstraintsForProperty("one")
            constrainedPropertyForPropertyOne shouldNot beNull()
            constrainedPropertyForPropertyOne.isCascaded should be(false)
            constrainedPropertyForPropertyOne.constraintDescriptors.size shouldBeEqual 1
            val constrainedPropertyForPropertyOneConstraintDescriptor = constrainedPropertyForPropertyOne.constraintDescriptors.first()
            constrainedPropertyForPropertyOneConstraintDescriptor.annotation.annotationClass should be(Min::class)

            val getOneMethodDescriptor = description.getConstraintsForMethod("getOne")
            getOneMethodDescriptor shouldNot beNull()
            getOneMethodDescriptor.elementClass should be(Int::class.javaPrimitiveType)
            getOneMethodDescriptor.parameterDescriptors.size shouldBeEqual 0
            getOneMethodDescriptor.constraintDescriptors.size shouldBeEqual 0
            getOneMethodDescriptor.returnValueDescriptor shouldNot beNull()
            getOneMethodDescriptor.returnValueDescriptor.constraintDescriptors.first().annotation.annotationClass should be(Min::class)
            getOneMethodDescriptor.returnValueDescriptor.elementClass should be(Int::class.javaPrimitiveType)

            description.getConstraintsForMethod("getTwo") should beNull() // not constrained
        }

        test("DescriptorFactory#constructors 1") {
            val description = descriptorFactory.describe(TestClasses.UsersRequest::class.java)
            description shouldNot beNull()
            description.elementClass shouldBeEqual TestClasses.UsersRequest::class.java
            description.hasConstraints() should be(false)
            description.isBeanConstrained should be(true)
            description.constraintDescriptors.size shouldBeEqual 0
            description.constrainedConstructors.size shouldBeEqual 1

            val constructorDescriptor = description.constrainedConstructors.first()
            constructorDescriptor.constraintDescriptors.isEmpty() should be(true)
            constructorDescriptor.parameterDescriptors.size shouldBeEqual 2 // 2 out 3 are constrained
            constructorDescriptor.parameterDescriptors.forEach { parameterDescriptor ->
                when (parameterDescriptor.name) {
                    "max" -> {
                        parameterDescriptor.index shouldBeEqual 0
                        parameterDescriptor.elementClass should be(Int::class.javaPrimitiveType)
                        parameterDescriptor.isCascaded should be(false)
                        parameterDescriptor.hasConstraints() should be(true)
                        parameterDescriptor.constrainedContainerElementTypes.size shouldBeEqual 0
                        parameterDescriptor.constraintDescriptors.size shouldBeEqual 1
                        parameterDescriptor.constraintDescriptors.first().annotation.annotationClass should be(Max::class)
                    }
                    "startDate" -> {
                        parameterDescriptor.index shouldBeEqual 1
                        parameterDescriptor.elementClass should be(LocalDate::class.java)
                        parameterDescriptor.isCascaded should be(false)
                        parameterDescriptor.hasConstraints() should be(true)
                        parameterDescriptor.constrainedContainerElementTypes.size shouldBeEqual 0
                        parameterDescriptor.constraintDescriptors.size shouldBeEqual 1
                        parameterDescriptor.constraintDescriptors.first().annotation.annotationClass should be(Past::class)
                    }
                }
            }

            description.constrainedProperties.size shouldBeEqual 2
            description.constrainedProperties.forEach { constrainedProperty ->
                when (constrainedProperty.propertyName) {
                    "max" -> {
                        constrainedProperty.elementClass should be(Int::class.javaPrimitiveType)
                        constrainedProperty.isCascaded should be(false)
                        constrainedProperty.hasConstraints() should be(true)
                        constrainedProperty.constrainedContainerElementTypes.size shouldBeEqual 0
                        constrainedProperty.constraintDescriptors.size shouldBeEqual 1
                        constrainedProperty.constraintDescriptors.first().annotation.annotationClass should be(Max::class)
                    }
                    "startDate" -> {
                        constrainedProperty.elementClass should be(LocalDate::class.java)
                        constrainedProperty.isCascaded should be(false)
                        constrainedProperty.hasConstraints() should be(true)
                        constrainedProperty.constrainedContainerElementTypes.size shouldBeEqual 0
                        constrainedProperty.constraintDescriptors.size shouldBeEqual 1
                        constrainedProperty.constraintDescriptors.first().annotation.annotationClass should be(Past::class)
                    }
                }
            }

            val maxMethodDescriptor = description.getConstraintsForMethod("getMax")
            maxMethodDescriptor.elementClass should be(Int::class.javaPrimitiveType)
            maxMethodDescriptor.parameterDescriptors.size shouldBeEqual 0
            val mmdRVD = maxMethodDescriptor.returnValueDescriptor
            mmdRVD.constraintDescriptors.size shouldBeEqual 1
            mmdRVD.constraintDescriptors.first().annotation.annotationClass should be(Max::class)

            val startDateMethodDescriptor = description.getConstraintsForMethod("getStartDate")
            startDateMethodDescriptor.elementClass should be(LocalDate::class.java)
            startDateMethodDescriptor.parameterDescriptors.size shouldBeEqual 0
            val sdmdRVD = startDateMethodDescriptor.returnValueDescriptor
            sdmdRVD.constraintDescriptors.size shouldBeEqual 1
            sdmdRVD.constraintDescriptors.first().annotation.annotationClass should be(Past::class)
        }

        test("DescriptorFactory#handle cycles") {
            // DescriptorFactory does not recurse so parses eventual cycles.
            val description = descriptorFactory.describe(TestClasses.A::class.java)
            description.elementClass should be(TestClasses.A::class.java)
            description.constrainedConstructors.size shouldBeEqual 1

            val constructor = description.constrainedConstructors.first()
            constructor.hasConstrainedParameters() should be(true)
            constructor.parameterDescriptors.size shouldBeEqual 2

            val idPropertyDescription = constructor.parameterDescriptors[0]
            idPropertyDescription.elementClass should be(String::class.java)
            idPropertyDescription.isCascaded should be(false)
            idPropertyDescription.constraintDescriptors.size shouldBeEqual 1
            val idPropertyAnnotation = idPropertyDescription.constraintDescriptors.first().annotation
            idPropertyAnnotation.annotationClass should be(NotEmpty::class)

            val bPropertyDescription = constructor.parameterDescriptors[1]
            bPropertyDescription.elementClass should be(TestClasses.B::class.java)
            bPropertyDescription.isCascaded should be(true)
            bPropertyDescription.constraintDescriptors.size shouldBeEqual 0

            description.constrainedProperties.size shouldBeEqual 2
            description.constrainedProperties.forEach { propertyDescriptor ->
                when (propertyDescriptor.propertyName) {
                    "id" -> {
                        propertyDescriptor.elementClass should be(String::class.java)
                        propertyDescriptor.isCascaded should be(false)
                        propertyDescriptor.constraintDescriptors.size shouldBeEqual 1
                        val idMemberAnnotation = propertyDescriptor.constraintDescriptors.first().annotation
                        idMemberAnnotation.annotationClass should be(NotEmpty::class)
                    }

                    "b" -> {
                        propertyDescriptor.elementClass should be(TestClasses.B::class.java)
                        propertyDescriptor.isCascaded should be(true)
                        propertyDescriptor.constraintDescriptors.size shouldBeEqual 0
                    }

                    else -> fail("")
                }
            }
        }

        test("DescriptorFactory#describeMethod") {
            val rentCarMethod = TestHelpers.getMethod(TestClasses.RentalStation::class.java, "rentCar", TestClasses.Customer::class.java, LocalDate::class.java, Int::class.java)
            val methodDescriptor = descriptorFactory.describeMethod(rentCarMethod)
            methodDescriptor shouldNot beNull()

            methodDescriptor!!.name should be("rentCar")
            methodDescriptor.parameterDescriptors.forEach { parameterDescriptor ->
                when (parameterDescriptor.name) {
                    "customer" -> {
                        parameterDescriptor.elementClass should be(TestClasses.Customer::class.java)
                        parameterDescriptor.isCascaded should be(false)
                        parameterDescriptor.constraintDescriptors.size shouldBeEqual 1
                        val notNullConstraint = parameterDescriptor.constraintDescriptors.first().annotation
                        notNullConstraint.annotationClass should be(NotNull::class)
                    }
                    "start" -> {
                        parameterDescriptor.elementClass should be(LocalDate::class.java)
                        parameterDescriptor.isCascaded should be(false)
                        parameterDescriptor.constraintDescriptors.size shouldBeEqual 2
                        parameterDescriptor.constraintDescriptors.forEach { constraintDescriptor ->
                            when {
                                constraintDescriptor.annotation.annotationClass == NotNull::class -> {} // pass
                                constraintDescriptor.annotation.annotationClass == Future::class -> {} // pass
                                else -> fail("")
                            }
                        }
                    }
                    "duration" -> {
                        parameterDescriptor.elementClass should be(Int::class.javaPrimitiveType)
                        parameterDescriptor.isCascaded should be(false)
                        parameterDescriptor.constraintDescriptors.size shouldBeEqual 1
                        val notNullConstraint = parameterDescriptor.constraintDescriptors.first().annotation
                        notNullConstraint.annotationClass should be(Min::class)
                    }
                    else ->
                        fail("")
                }
            }

            methodDescriptor.returnValueDescriptor should beNull()
        }

        test("DescriptorFactory#cascading") {
            // Java record class with type annotations is properly described for cascading
            val descriptor = descriptorFactory.describe(JavaTestClass::class.java)
            descriptor.constrainedConstructors.size shouldBeEqual 1
            val constructorDescriptor = descriptor.constrainedConstructors.first()
            constructorDescriptor.parameterDescriptors.size shouldBeEqual 1
            val parameterDescriptor = constructorDescriptor.parameterDescriptors.first()
            parameterDescriptor.elementClass should be(
                List::class.createType(
                    arguments =
                    listOf(
                        KTypeProjection(
                            KVariance.INVARIANT,
                            String::class.createType()
                        )
                    )
                ).jvmErasure.java
            )
            parameterDescriptor.isCascaded should be(false) // NOT cascaded
            parameterDescriptor.constrainedContainerElementTypes.size shouldBeEqual 1
            val parameterConstrainedContainerElementType = parameterDescriptor.constrainedContainerElementTypes.first()
            parameterConstrainedContainerElementType.isCascaded should be(true) // IS cascaded
            parameterConstrainedContainerElementType.containerClass should be (
                List::class.createType(
                    arguments =
                    listOf(
                        KTypeProjection(
                            KVariance.INVARIANT,
                            String::class.createType()
                        )
                    )
                ).jvmErasure.java
            )
            parameterConstrainedContainerElementType.elementClass should be(String::class.java)
        }
    }
}
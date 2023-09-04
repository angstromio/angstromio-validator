package angstromio.validation

import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.be
import io.kotest.matchers.equals.shouldBeEqual
import io.kotest.matchers.nulls.beNull
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNot
import jakarta.validation.constraints.Future
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import org.hibernate.validator.internal.metadata.core.ConstraintHelper
import java.time.LocalDate
import kotlin.reflect.KCallable
import kotlin.reflect.KFunction
import kotlin.reflect.full.createType
import kotlin.reflect.jvm.kotlinFunction

class DescriptorFactoryTest : FunSpec() {
    private val descriptorFactory: DescriptorFactory =
        DescriptorFactory(128L, ConstraintHelper.forAllBuiltinConstraints())

    init {
        test("DescriptorFactory#secondary constructors") {
            val description = descriptorFactory.describe(testclasses.WithSecondaryConstructor::class)
            description shouldNot beNull()
            description.clazz shouldBeEqual testclasses.WithSecondaryConstructor::class
            description.type shouldBeEqual testclasses.WithSecondaryConstructor::class.java
            description.constructors.size shouldBeEqual 2
            description.constructors.forEach { constructor ->
                constructor.annotations.isEmpty() shouldBe true // neither constructor has any constructor annotations
                constructor.members.size shouldBeEqual 2 // both have two members
                constructor.members.forEach { (name, propertyDescription) ->
                    // neither is cascaded
                    propertyDescription.isCascaded shouldBe false
                    when (name) {
                        "four" -> {
                            // there should be a @NotBlank annotation
                            propertyDescription.annotations.size shouldBeEqual 1
                            propertyDescription.annotations[0].annotationClass shouldBeEqual NotBlank::class
                        }

                        "one" -> {
                            // there should be @Min annotation
                            propertyDescription.annotations.size shouldBeEqual 1
                            propertyDescription.annotations[0].annotationClass shouldBeEqual Min::class
                        }

                        else -> {
                            propertyDescription.annotations.isEmpty() shouldBe true
                        }
                    }
                }
            }
            description.annotations.size shouldBeEqual 0 // no class annotations
            description.members.size shouldBeEqual 2 // one: Int, two: Int
            description.members.forEach { (name, propertyDescription) ->
                (name == "one" || name == "two") shouldBe true
                // "one" carries the @NotBlank annotation copied from the constructor param
                val annotationCount = if (name == "one") 1 else 0
                propertyDescription.annotations.size shouldBeEqual annotationCount
                // neither is cascaded
                propertyDescription.isCascaded shouldBe false
            }

            // no constrained methods
            description.methods.size shouldBeEqual 0
        }

        test("DescriptorFactory#handle cycles") {
            // DescriptorFactory does not recurse so parses eventual cycles.
            val description = descriptorFactory.describe(testclasses.A::class)
            description.clazz should be(testclasses.A::class)
            description.constructors.size shouldBeEqual 1

            val constructor = description.constructors[0]
            constructor.members.size shouldBeEqual 2

            val idPropertyDescription = constructor.members["id"]!!
            idPropertyDescription.clazz should be(String::class)
            idPropertyDescription.isCascaded should be(false)
            idPropertyDescription.cascadedType should beNull()
            idPropertyDescription.annotations.size shouldBeEqual 1
            val idPropertyAnnotation = idPropertyDescription.annotations[0]
            idPropertyAnnotation.annotationClass should be(NotEmpty::class)

            val bPropertyDescription = constructor.members["b"]!!
            bPropertyDescription.clazz should be(testclasses.B::class)
            bPropertyDescription.isCascaded should be(true)
            bPropertyDescription.cascadedType should be(testclasses.B::class.createType())
            bPropertyDescription.annotations.size shouldBeEqual 0

            description.members.size shouldBeEqual 2
            val idMemberDescription = description.members["id"]!!
            idMemberDescription.clazz should be(String::class)
            idMemberDescription.isCascaded should be(false)
            idMemberDescription.cascadedType should beNull()
            idMemberDescription.annotations.size shouldBeEqual 1
            val idMemberAnnotation = idMemberDescription.annotations[0]
            idMemberAnnotation.annotationClass should be(NotEmpty::class)

            val bMemberDescription = description.members["b"]!!
            bMemberDescription.clazz should be(testclasses.B::class)
            bMemberDescription.isCascaded should be(true)
            bMemberDescription.cascadedType should be(testclasses.B::class.createType())
            bMemberDescription.annotations.size shouldBeEqual 0
        }

        test("DescriptorFactory#describeMethod") {
            val rentCarMethod = getMethod<Unit>(testclasses.RentalStation::class.java, "rentCar", testclasses.Customer::class.java, LocalDate::class.java, Int::class.java)
            val methodDescriptor = descriptorFactory.describeMethod(rentCarMethod)
            methodDescriptor shouldNot beNull()
            methodDescriptor!!.kind should be(angstromio.validation.metadata.ExecutableDescriptor.Kind.Method)
            methodDescriptor.annotations.size shouldBeEqual 0
            methodDescriptor.members.size shouldBeEqual 3
            methodDescriptor.members.forEach { (name, propertyDescriptor) ->
                when (name) {
                    "customer" -> {
                        propertyDescriptor.clazz should be(testclasses.Customer::class)
                        propertyDescriptor.kotlinType should be(testclasses.Customer::class.createType())
                        propertyDescriptor.isCascaded should be(false)
                        propertyDescriptor.cascadedType should beNull()
                        propertyDescriptor.annotations.size shouldBeEqual 1
                        val notNullConstraint = propertyDescriptor.annotations[0]
                        notNullConstraint.annotationClass should be(NotNull::class)
                    }
                    "start" -> {
                        propertyDescriptor.clazz should be(LocalDate::class)
                        propertyDescriptor.kotlinType should be(LocalDate::class.createType())
                        propertyDescriptor.isCascaded should be(false)
                        propertyDescriptor.cascadedType should beNull()
                        propertyDescriptor.annotations.size shouldBeEqual 2
                        propertyDescriptor.annotations.forEach { annotation ->
                            when {
                                annotation.annotationClass == NotNull::class -> {} // pass
                                annotation.annotationClass == Future::class -> {} // pass
                                else -> fail("")
                            }
                        }
                    }
                    "duration" -> {
                        propertyDescriptor.clazz should be(Int::class)
                        propertyDescriptor.kotlinType should be(Int::class.createType())
                        propertyDescriptor.isCascaded should be(false)
                        propertyDescriptor.cascadedType should beNull()
                        propertyDescriptor.annotations.size shouldBeEqual 1
                        val notNullConstraint = propertyDescriptor.annotations[0]
                        notNullConstraint.annotationClass should be(Min::class)
                    }
                    else ->
                        fail("")
                }
            }
            methodDescriptor.callable shouldNot beNull()
            methodDescriptor.callable.name should be("rentCar")
            methodDescriptor.callable.returnType should be(Unit::class.createType())

            methodDescriptor.declaringClass should be(testclasses.RentalStation::class.java)
            methodDescriptor.declaringKClass should be(testclasses.RentalStation::class)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <R : Any> getMethod(
        clazz: Class<*>,
        methodName: String,
        vararg parameterTypes: Class<*>
    ): KFunction<R> {
        return clazz.getDeclaredMethod(methodName, *parameterTypes).kotlinFunction as KFunction<R>
    }

    private fun <T : Any> getConstructor(clazz: Class<T>, vararg parameterTypes: Class<*>): KCallable<T> {
        return clazz.getDeclaredConstructor(*parameterTypes).kotlinFunction as KCallable<T>
    }
}
package angstromio.validation

import angstromio.util.extensions.Anys.isInstanceOf
import angstromio.validation.cfg.ConstraintMapping
import angstromio.validation.constraints.CountryCode
import angstromio.validation.constraints.ISO3166CountryCodeConstraintValidator
import angstromio.validation.constraints.InvalidConstraint
import angstromio.validation.constraints.InvalidConstraintValidator
import angstromio.validation.constraints.NotEmptyAnyConstraintValidator
import angstromio.validation.constraints.NotEmptyPathConstraintValidator
import angstromio.validation.constraints.PostConstructValidation
import angstromio.validation.constraints.StateConstraint
import angstromio.validation.constraints.StateConstraintPayload
import angstromio.validation.constraints.ValidPassengerCount
import angstromio.validation.constraints.ValidPassengerCountConstraintValidator
import angstromio.validation.extensions.getDynamicPayload
import io.kotest.matchers.be
import io.kotest.matchers.equals.shouldBeEqual
import io.kotest.matchers.nulls.beNull
import io.kotest.matchers.should
import io.kotest.matchers.shouldNot
import jakarta.validation.ConstraintDeclarationException
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintViolation
import jakarta.validation.ConstraintViolationException
import jakarta.validation.UnexpectedTypeException
import jakarta.validation.Validation
import jakarta.validation.ValidationException
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import jakarta.validation.metadata.BeanDescriptor
import jakarta.validation.metadata.ConstructorDescriptor
import jakarta.validation.metadata.ContainerElementTypeDescriptor
import jakarta.validation.metadata.MethodDescriptor
import jakarta.validation.metadata.PropertyDescriptor
import jakarta.validation.metadata.ReturnValueDescriptor
import org.hibernate.validator.HibernateValidatorConfiguration
import org.hibernate.validator.internal.util.annotation.AnnotationDescriptor
import org.hibernate.validator.internal.util.annotation.AnnotationFactory
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate
import java.util.*
import kotlin.reflect.KTypeProjection
import kotlin.reflect.KVariance
import kotlin.reflect.full.createType
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.javaType
import kotlin.reflect.jvm.jvmErasure

class ValidatorTest : AssertViolationTest() {
    companion object {
        val DefaultAddress: TestClasses.Address =
            TestClasses.Address(line1 = "1234 Main St", city = "Anywhere", state = "CA", zipcode = "94102")

        val CustomConstraintMappings: Set<ConstraintMapping> =
            setOf(
                ConstraintMapping(
                    ValidPassengerCount::class.java,
                    ValidPassengerCountConstraintValidator::class.java,
                    includeExistingValidators = false
                ),
                ConstraintMapping(
                    InvalidConstraint::class.java,
                    InvalidConstraintValidator::class.java
                )
            )
    }

    override val validator: DataClassValidator =
        DataClassValidator
            .builder()
            .withConstraintMappings(CustomConstraintMappings)
            .validator()

    init {
        afterSpec {
            validator.close()
        }

        test("DataClassValidator#with custom message interpolator") {
            val withCustomMessageInterpolator = DataClassValidator.builder()
                .withMessageInterpolator(WrongMessageInterpolator())
                .validator()
            try {
                val car = TestClasses.SmallCar(null, "DD-AB-123", 4)
                val violations = withCustomMessageInterpolator.validate(car)
                assertViolations(
                    violations = violations,
                    withViolations = listOf(
                        WithViolation(
                            "manufacturer",
                            "Whatever you entered was wrong",
                            null,
                            TestClasses.SmallCar::class.java,
                            car
                        )
                    )
                )
            } finally {
                withCustomMessageInterpolator.close()
            }
        }

        test("DataClassValidator#nested data class definition") {
            val value = OuterObject.InnerObject.SuperNestedDataClass(4L)

            val violations = validator.validate(value)
            violations.size shouldBeEqual 1
            val violation = violations.first()
            violation.propertyPath.toString() should be("id")
            violation.message should be("must be greater than or equal to 5")
            violation.rootBeanClass should be(OuterObject.InnerObject.SuperNestedDataClass::class.java)
            violation.rootBean should be(value)
            violation.invalidValue shouldBeEqual 4L
        }

        test("DataClassValidator#unicode field name") {
            val value = TestClasses.UnicodeNameDataClass(5, "")

            assertViolations(
                obj = value,
                withViolations = listOf(
                    WithViolation(
                        "name",
                        "must not be empty",
                        "",
                        TestClasses.UnicodeNameDataClass::class.java,
                        value,
                        getValidationAnnotations(TestClasses.UnicodeNameDataClass::class.java, "name").last()
                    ),
                    WithViolation(
                        "winning-id",
                        "must be less than or equal to 3",
                        5,
                        TestClasses.UnicodeNameDataClass::class.java,
                        value,
                        getValidationAnnotations(TestClasses.UnicodeNameDataClass::class.java, "winning-id").last()
                    ),
                )
            )
        }

        test("DataClassValidator#correctly handle not cascading for a non-data class type") {
            val value = TestClasses.WithBogusCascadedField("DD-AB-123", listOf(1, 2, 3))
            assertViolations(value)
        }

        test("DataClassValidator#with InvalidConstraint") {
            val value = TestClasses.AlwaysFails("fails")

            val e = assertThrows<RuntimeException> {
                validator.validate(value)
            }
            e.message should be("FORCED TEST EXCEPTION")
        }

        test("DataClassValidator#with failing PostConstructValidation") {
            // fails the @NotEmpty on the id field but the post construct validation blows up
            val value = TestClasses.AlwaysFailsPostConstructValidation("")

            val e = assertThrows<ValidationException> {
                validator.validate(value)
            }
            e.message should be("oh noes!")
        }

        test("DataClassValidator#isConstraintValidator") {
            val stateConstraint =
                AnnotationFactory.create(
                    AnnotationDescriptor.Builder(
                        StateConstraint::class.java,
                        emptyMap<String, Any>()
                    ).build()
                )
            validator.isConstraintAnnotation(stateConstraint) should be(true)
        }

        test("DataClassValidator#constraint mapping 1") {
            val value = TestClasses.PathNotEmpty(TestClasses.TestPath.Empty, "abcd1234")

            // no validator registered `@NotEmpty` for Path type
            assertThrows<UnexpectedTypeException> {
                validator.validate(value)
            }

            // define an additional constraint validator for `@NotEmpty` which works for
            // Path types. note we include all existing validators
            val pathCustomConstraintMapping =
                ConstraintMapping(NotEmpty::class.java, NotEmptyPathConstraintValidator::class.java)

            val withPathConstraintValidator: DataClassValidator =
                DataClassValidator
                    .builder()
                    .withConstraintMapping(pathCustomConstraintMapping).validator()

            try {
                val violations = withPathConstraintValidator.validate(value)
                violations.size should be(1)
                violations.first().propertyPath.toString() should be("path")
                violations.first().message should be("must not be empty")
                violations.first().rootBeanClass should be(TestClasses.PathNotEmpty::class.java)
                violations.first().rootBean should be(value)
                violations.first().invalidValue should be(TestClasses.TestPath.Empty)

                // valid instance
                withPathConstraintValidator
                    .validate(TestClasses.PathNotEmpty(TestClasses.TestPath("node"), "abcd1234"))
                    .isEmpty() should be(true)
            } finally {
                withPathConstraintValidator.close()
            }

            // define an additional constraint validator for `@NotEmpty` which works for
            // Path types. note we do include all existing validators which will cause validation to
            // fail since we can no longer find a validator for `@NotEmpty` which works for string
            val pathCustomConstraintMapping2 =
                ConstraintMapping(
                    NotEmpty::class.java,
                    NotEmptyPathConstraintValidator::class.java,
                    includeExistingValidators = false
                )

            val withPathConstraintValidator2: DataClassValidator =
                DataClassValidator.builder()
                    .withConstraintMapping(pathCustomConstraintMapping2).validator()
            try {
                assertThrows<UnexpectedTypeException> {
                    withPathConstraintValidator2.validate(value)
                }
            } finally {
                withPathConstraintValidator2.close()
            }
        }

        test("DataClassValidator#constraint mapping 2") {
            // attempting to add a mapping for the same annotation multiple times will result in an error
            val pathCustomConstraintMapping1 =
                ConstraintMapping(NotEmpty::class.java, NotEmptyPathConstraintValidator::class.java)
            val pathCustomConstraintMapping2 =
                ConstraintMapping(NotEmpty::class.java, NotEmptyAnyConstraintValidator::class.java)

            val e1 = assertThrows<ValidationException> {
                DataClassValidator.builder()
                    .withConstraintMappings(setOf(pathCustomConstraintMapping1, pathCustomConstraintMapping2))
                    .validator()
            }
            e1.message!!.endsWith(
                "jakarta.validation.constraints.NotEmpty is configured more than once via the programmatic constraint definition API."
            ) should be(
                true
            )

            val pathCustomConstraintMapping3 =
                ConstraintMapping(NotEmpty::class.java, NotEmptyPathConstraintValidator::class.java)
            val pathCustomConstraintMapping4 =
                ConstraintMapping(
                    NotEmpty::class.java,
                    NotEmptyAnyConstraintValidator::class.java,
                    includeExistingValidators = false
                )

            val e2 = assertThrows<ValidationException> {
                DataClassValidator.builder()
                    .withConstraintMappings(setOf(pathCustomConstraintMapping3, pathCustomConstraintMapping4))
                    .validator()
            }

            e2.message!!.endsWith(
                "jakarta.validation.constraints.NotEmpty is configured more than once via the programmatic constraint definition API."
            ) should be(
                true
            )

            val pathCustomConstraintMapping5 =
                ConstraintMapping(
                    NotEmpty::class.java,
                    NotEmptyPathConstraintValidator::class.java,
                    includeExistingValidators = false
                )
            val pathCustomConstraintMapping6 =
                ConstraintMapping(NotEmpty::class.java, NotEmptyAnyConstraintValidator::class.java)

            val e3 = assertThrows<ValidationException> {
                DataClassValidator.builder()
                    .withConstraintMappings(setOf(pathCustomConstraintMapping5, pathCustomConstraintMapping6))
                    .validator()
            }

            e3.message!!.endsWith(
                "jakarta.validation.constraints.NotEmpty is configured more than once via the programmatic constraint definition API."
            ) should be(
                true
            )

            val pathCustomConstraintMapping7 =
                ConstraintMapping(
                    NotEmpty::class.java,
                    NotEmptyPathConstraintValidator::class.java,
                    includeExistingValidators = false
                )
            val pathCustomConstraintMapping8 =
                ConstraintMapping(
                    NotEmpty::class.java,
                    NotEmptyAnyConstraintValidator::class.java,
                    includeExistingValidators = false
                )

            val e4 = assertThrows<ValidationException> {
                DataClassValidator.builder()
                    .withConstraintMappings(setOf(pathCustomConstraintMapping7, pathCustomConstraintMapping8))
                    .validator()
            }

            e4.message!!.endsWith(
                "jakarta.validation.constraints.NotEmpty is configured more than once via the programmatic constraint definition API."
            ) should be(
                true
            )
        }

        test("DataClassValidator#payload") {
            val address =
                TestClasses.Address(line1 = "1234 Main St", city = "Anywhere", state = "PA", zipcode = "94102")
            val violations = validator.validate(address)
            violations.size shouldBeEqual 1
            val violation = violations.first()
            val payload = violation.getDynamicPayload(StateConstraintPayload::class.java)
            assert(payload != null)
            payload?.let {
                StateConstraintPayload::class.java.isAssignableFrom(payload::class.java) should be(true)
                payload should be(StateConstraintPayload("PA", "CA"))
            }
        }

        test("DataClassValidator#post construct validation payload") {
            val user = TestClasses.NestedUser(
                id = "abcd1234",
                person = TestClasses.Person("abcd1234", "R. Franklin", DefaultAddress),
                gender = "F",
                job = ""
            )
            val violations = validator.validate(user)
            violations.size shouldBeEqual 1
            val violation = violations.first()
            val payload = violation.getDynamicPayload(TestClasses.NestedUserPayload::class.java)
            assert(payload != null)
            payload?.let {
                TestClasses.NestedUserPayload::class.java.isAssignableFrom(payload::class.java) should be(true)
                payload should be(TestClasses.NestedUserPayload("abcd1234", ""))
            }
        }

        test("DataClassValidator#newInstance") {
            val stateConstraint =
                AnnotationFactory.create(
                    AnnotationDescriptor.Builder(
                        StateConstraint::class.java,
                        emptyMap<String, Any>()
                    ).build()
                )
            validator.isConstraintAnnotation(stateConstraint) should be(true)

            val minConstraint = AnnotationFactory.create(
                AnnotationDescriptor.Builder(Min::class.java, mapOf("value" to 1L)).build()
            )
            minConstraint.groups.isEmpty() should be(true)
            minConstraint.message should be("{jakarta.validation.constraints.Min.message}")
            minConstraint.payload.isEmpty() should be(true)
            minConstraint.value shouldBeEqual 1L
            validator.isConstraintAnnotation(minConstraint) should be(true)

            val patternConstraint = AnnotationFactory.create(
                AnnotationDescriptor.Builder(
                    Pattern::class.java, mapOf("regexp" to ".*", "flags" to arrayOf(Pattern.Flag.CASE_INSENSITIVE))
                ).build()
            )
            patternConstraint.groups.isEmpty() should be(true)
            patternConstraint.message should be("{jakarta.validation.constraints.Pattern.message}")
            patternConstraint.payload.isEmpty() should be(true)
            patternConstraint.regexp should be(".*")
            patternConstraint.flags should be(arrayOf(Pattern.Flag.CASE_INSENSITIVE))
            validator.isConstraintAnnotation(patternConstraint) should be(true)
        }

        @Suppress("UNCHECKED_CAST")
        test("DataClassValidator#constraint validators by annotation class") {
            val validators = validator.findConstraintValidators(CountryCode::class.java)
            validators.size shouldBeEqual 1
            validators.first()::class.java.name should be(
                ISO3166CountryCodeConstraintValidator::class.java.name
            )

            (validators.first() as ConstraintValidator<CountryCode, String>).isValid("US", null) should be(true)
        }

        test("DataClassValidator#nulls") {
            val car = TestClasses.SmallCar(null, "DD-AB-123", 4)
            assertViolations(
                obj = car,
                withViolations = listOf(
                    WithViolation("manufacturer", "must not be null", null, TestClasses.SmallCar::class.java, car)
                )
            )
        }

        test("DataClassValidator#not a data class") {
            val value = TestClasses.NotADataClass(null, "DD-AB-123", 4, "NotAUUID")
            val e = assertThrows<ValidationException> {
                validator.validate(value)
            }
            e.message should be("${TestClasses.NotADataClass::class.java} is not a valid data class.")

            // underlying validator doesn't find the constraintDescriptors (either executable nor inherited)
            val violations = validator.underlying.validate(value).toSet()
            violations.isEmpty() should be(true)
        }

        test("DataClassValidator#fails with @set meta annotation") {
            val value = TestClasses.WithMetaSetAnnotation("")

            assertThrows<ConstraintDeclarationException> { assertViolations(obj = value, withViolations = emptyList()) }
            // this also fails with underlying validator
            assertThrows<ConstraintDeclarationException> { validator.underlying.validate(value).toSet() }

            val cascadingValue = TestClasses.WithCascadingSetAnnotation(emptyList())
            assertThrows<ConstraintDeclarationException> {
                assertViolations(
                    obj = cascadingValue,
                    withViolations = emptyList()
                )
            }
            // this also fails with underlying validator
            assertThrows<ConstraintDeclarationException> { validator.underlying.validate(cascadingValue).toSet() }
        }

        test("DataClassValidator#works with @get meta annotation") {
            val value = TestClasses.WithMetaGetAnnotation("")

            // getter field constraintDescriptors should work
            assertViolations(
                obj = value,
                withViolations = listOf(
                    WithViolation(
                        "id",
                        "must not be empty",
                        "",
                        TestClasses.WithMetaGetAnnotation::class.java,
                        value,
                        getValidationAnnotations(TestClasses.WithMetaGetAnnotation::class.java, "id").first()
                    )
                )
            )

            // this also works with the underlying validator
            val violations = validator.underlying.validate(value).toSet()
            violations.size shouldBeEqual 1
            val violation = violations.first()
            violation.message should be("must not be empty")
            violation.propertyPath.toString() should be("id")
            violation.invalidValue should be("")
            violation.rootBeanClass should be(TestClasses.WithMetaGetAnnotation::class.java)
            violation.rootBean should be(value)
            violation.leafBean should be(value)
        }

        test("DataClassValidator#works with @field meta annotation") {
            val value = TestClasses.WithMetaFieldAnnotation("")

            assertViolations(
                obj = value,
                withViolations = listOf(
                    WithViolation(
                        "id",
                        "must not be empty",
                        "",
                        TestClasses.WithMetaFieldAnnotation::class.java,
                        value,
                        getValidationAnnotations(TestClasses.WithMetaFieldAnnotation::class.java, "id").first()
                    )
                )
            )

            // also works with underlying validator
            val violations = validator.underlying.validate(value).toSet()
            violations.size shouldBeEqual 1
            val violation = violations.first()
            violation.message should be("must not be empty")
            violation.propertyPath.toString() should be("id")
            violation.invalidValue should be("")
            violation.rootBeanClass should be(TestClasses.WithMetaFieldAnnotation::class.java)
            violation.rootBean should be(value)
            violation.leafBean should be(value)
        }

        test("DataClassValidator#works with @field meta annotation 1") {
            val value = TestClasses.WithPartialMetaFieldAnnotation("")

            assertViolations(
                obj = value,
                withViolations = listOf(
                    WithViolation(
                        "id",
                        "must not be empty",
                        "",
                        TestClasses.WithPartialMetaFieldAnnotation::class.java,
                        value,
                        getValidationAnnotations(TestClasses.WithPartialMetaFieldAnnotation::class.java, "id").last()
                    ),
                    WithViolation(
                        "id",
                        "size must be between 2 and 10",
                        "",
                        TestClasses.WithPartialMetaFieldAnnotation::class.java,
                        value,
                        getValidationAnnotations(TestClasses.WithPartialMetaFieldAnnotation::class.java, "id").first()
                    )
                )
            )
        }

        test("DataClassValidator#validateValue 1") {
            val violations = validator.validateValue(TestClasses.OneOfListExample::class.java, "enumValue", listOf("g"))
            violations.size shouldBeEqual 1
            val violation = violations.first()
            violation.message should be("g not one of [a, B, c]")
            violation.propertyPath.toString() should be("enumValue")
            violation.invalidValue should be(listOf("g"))
            violation.rootBeanClass should be(TestClasses.OneOfListExample::class.java)
            violation.rootBean should beNull()
            violation.leafBean should beNull()
        }

        test("DataClassValidator#validateValue 2") {
            val descriptor = validator.getConstraintsForClass(TestClasses.OneOfListExample::class.java)
            val violations = validator.validateValue<TestClasses.OneOfListExample>(descriptor, "enumValue", listOf("g"))
            violations.size shouldBeEqual 1
            val violation = violations.first()
            violation.message should be("g not one of [a, B, c]")
            violation.propertyPath.toString() should be("enumValue")
            violation.invalidValue should be(listOf("g"))
            violation.rootBeanClass should be(TestClasses.OneOfListExample::class.java)
            violation.rootBean should beNull()
            violation.leafBean should beNull()
        }

        test("DataClassValidator#validateProperty 1") {
            val value = TestClasses.CustomerAccount("", null)
            val violations = validator.validateProperty(value, "accountName")
            violations.size should be(1)
            val violation = violations.first()
            violation.message should be("must not be empty")
            violation.propertyPath.toString() should be("accountName")
            violation.invalidValue should be("")
            violation.rootBeanClass should be(TestClasses.CustomerAccount::class.java)
            violation.rootBean should be(value)
            violation.leafBean should be(value)
        }

        test("DataClassValidator#validateProperty 2") {
            validator
                .validateProperty(
                    TestClasses.MinIntExample(numberValue = 2),
                    "numberValue"
                ).isEmpty() should be(true)
            assertViolations(obj = TestClasses.MinIntExample(numberValue = 2))

            val invalid = TestClasses.MinIntExample(numberValue = 0)
            val violations = validator
                .validateProperty(
                    invalid,
                    "numberValue"
                )
            violations.size shouldBeEqual 1
            val violation = violations.first()
            violation.propertyPath.toString() should be("numberValue")
            violation.message should be("must be greater than or equal to 1")
            violation.rootBeanClass should be(TestClasses.MinIntExample::class.java)
            violation.rootBean.javaClass.name should be(invalid.javaClass.name)

            assertViolations(
                obj = invalid,
                withViolations = listOf(
                    WithViolation(
                        "numberValue",
                        "must be greater than or equal to 1",
                        0,
                        TestClasses.MinIntExample::class.java,
                        invalid,
                        getValidationAnnotations(TestClasses.MinIntExample::class.java, "numberValue").first()
                    )
                )
            )
        }

        test("DataClassValidator#validateFieldValue") {
            val constraints: Map<Class<out Annotation>, Map<String, Any>> =
                mapOf(jakarta.validation.constraints.Size::class.java to mapOf("min" to 5, "max" to 7))

            // size doesn't work for int types
            assertThrows<UnexpectedTypeException> {
                validator.validateFieldValue(constraints, "intField", 4)
            }

            // should work fine for collections of the right size
            validator
                .validateFieldValue(constraints, "javaSetIntField", newJavaSet(6)).isEmpty() should be(true)
            validator
                .validateFieldValue(constraints, "seqIntField", listOf(1, 2, 3, 4, 5, 6)).isEmpty() should be(true)
            validator
                .validateFieldValue(constraints, "setIntField", setOf(1, 2, 3, 4, 5, 6)).isEmpty() should be(true)
            validator
                .validateFieldValue(constraints, "arrayIntField", arrayOf(1, 2, 3, 4, 5, 6)).isEmpty() should be(
                true
            )
            validator
                .validateFieldValue(constraints, "stringField", "123456").isEmpty() should be(true)

            // will fail for types of the wrong size
            val violations =
                validator.validateFieldValue(constraints, "seqIntField", listOf(1, 2, 3))
            violations.size should be(1)
            val violation: ConstraintViolation<Any> = violations.first()
            violation.message should be("size must be between 5 and 7")
            violation.propertyPath.toString() should be("seqIntField")
            violation.invalidValue should be(listOf(1, 2, 3))
            violation.rootBeanClass should be(listOf(1, 2, 3)::class.java)
            violation.rootBean should beNull()
            violation.leafBean should beNull()

            val invalidJavaSet = newJavaSet(3)
            val violations2 = validator
                .validateFieldValue(constraints, "javaSetIntField", invalidJavaSet)
            violations2.size should be(1)
            val violation2: ConstraintViolation<Any> = violations.first()
            violation2.message should be("size must be between 5 and 7")
            violation2.propertyPath.toString() should be("seqIntField")
            violation2.invalidValue should be(listOf(1, 2, 3))
            violation2.rootBeanClass should be(listOf(1, 2, 3)::class.java)
            violation2.rootBean should beNull()
            violation2.leafBean should beNull()

            val constraintsWithGroup: Map<Class<out Annotation>, Map<String, Any>> =
                mapOf(
                    jakarta.validation.constraints.Size::class.java to mapOf(
                        "min" to 5,
                        "max" to 7,
                        "groups" to arrayOf(TestClasses.PersonCheck::class.java)
                    )
                ) // groups MUST be an array type

            // constraint group is not passed (not activated)
            validator
                .validateFieldValue(constraintsWithGroup, "seqIntField", listOf(1, 2, 3)).isEmpty() should be(true)
            // constraint group is passed (activated)
            val violationsWithGroup = validator
                .validateFieldValue(
                    constraintsWithGroup,
                    "seqIntField",
                    listOf(1, 2, 3),
                    TestClasses.PersonCheck::class.java
                )
            violationsWithGroup.size should be(1)
            val violationWg = violationsWithGroup.first()
            violationWg.message should be("size must be between 5 and 7")
            violationWg.propertyPath.toString() should be("seqIntField")
            violationWg.invalidValue should be(listOf(1, 2, 3))
            violationWg.rootBeanClass should be(listOf(1, 2, 3)::class.java)
            violationWg.rootBean should beNull()
            violationWg.leafBean should beNull()

            val violationsSt =
                validator.validateFieldValue(constraints, "setIntField", setOf(1, 2, 3, 4, 5, 6, 7, 8))
            violationsSt.size should be(1)
            val violationSt = violationsSt.first()
            violationSt.message should be("size must be between 5 and 7")
            violationSt.propertyPath.toString() should be("setIntField")
            violationSt.invalidValue should be(setOf(1, 2, 3, 4, 5, 6, 7, 8))
            violationSt.rootBeanClass should be(setOf(1, 2, 3, 4, 5, 6, 7, 8)::class.java)
            violationSt.rootBean should beNull()
            violationSt.leafBean should beNull()

            validator
                .validateFieldValue(
                    constraints = mapOf(jakarta.validation.constraints.NotEmpty::class.java to emptyMap()),
                    fieldName = "data",
                    value = "Hello, world"
                ).isEmpty() should be(true)
        }

        test("DataClassValidator#groups support") {
            // see: https://docs.jboss.org/hibernate/stable/dataClassValidator/reference/en-US/html_single/#chapter-groups
            val toTest = TestClasses.WithPersonCheck("", "Jane Doe")
            // PersonCheck group not enabled, no violations
            assertViolations(obj = toTest)
            // Completely diff group enabled, no violations
            validator.validate(obj = toTest, TestClasses.OtherCheck::class.java)
            // PersonCheck group enabled
            assertViolations(
                obj = toTest,
                groups = listOf(TestClasses.PersonCheck::class.java),
                withViolations = listOf(
                    WithViolation("id", "must not be empty", "", TestClasses.WithPersonCheck::class.java, toTest)
                )
            )

            // multiple groups with PersonCheck group enabled
            assertViolations(
                obj = toTest,
                groups = listOf(TestClasses.OtherCheck::class.java, TestClasses.PersonCheck::class.java),
                withViolations = listOf(
                    WithViolation("id", "must not be empty", "", TestClasses.WithPersonCheck::class.java, toTest)
                )
            )
        }

        test("DataClassValidator#isCascaded post construct validation - defined fields") {
            // nested post construct validation fields
            val owner = TestClasses.Person(id = "", name = "A. Einstein", address = DefaultAddress)
            val beetleOwners = listOf(TestClasses.Person(id = "9999", name = "", address = DefaultAddress))
            val beetlePassengers =
                listOf(TestClasses.Person(id = "1001", name = "R. Franklin", address = DefaultAddress))
            val beetleOwnershipStart = LocalDate.now()
            val beetleOwnershipEnd = beetleOwnershipStart.plusYears(10)
            val beetleWarrantyEnd = beetleOwnershipStart.plusYears(3)
            val vwCarMake = CarMake.Volkswagen
            val car = TestClasses.Car(
                id = 1234,
                make = vwCarMake,
                model = "Beetle",
                year = 1970,
                owners = beetleOwners,
                licensePlate = "CA123",
                numDoors = 2,
                manual = true,
                ownershipStart = beetleOwnershipStart,
                ownershipEnd = beetleOwnershipEnd,
                warrantyStart = null,
                warrantyEnd = beetleWarrantyEnd,
                passengers = beetlePassengers
            )
            val driver = TestClasses.Driver(
                person = owner,
                car = car
            )

            // no PersonCheck -- doesn't fail
            assertViolations(obj = driver, groups = listOf(TestClasses.PersonCheck::class.java))
            // with default group - fail
            assertViolations(
                obj = driver,
                withViolations = listOf(
                    WithViolation(
                        "car.owners[0].name",
                        "must not be empty",
                        "",
                        TestClasses.Driver::class.java,
                        driver
                    ),
                    WithViolation("car.validateId", "id may not be even", car, TestClasses.Driver::class.java, driver),
                    WithViolation(
                        "car.warrantyTimeValid.warrantyEnd",
                        "both warrantyStart and warrantyEnd are required for a valid range",
                        beetleWarrantyEnd,
                        TestClasses.Driver::class.java,
                        driver
                    ),
                    WithViolation(
                        "car.warrantyTimeValid.warrantyStart",
                        "both warrantyStart and warrantyEnd are required for a valid range",
                        null,
                        TestClasses.Driver::class.java,
                        driver
                    ),
                    WithViolation(
                        "car.year",
                        "must be greater than or equal to 2000",
                        1970,
                        TestClasses.Driver::class.java,
                        driver
                    ),
                    WithViolation("person.id", "must not be empty", "", TestClasses.Driver::class.java, driver)
                )
            )

            // Validation with the default group does not return any violations with the standard
            // Hibernate Validator because the constraint constraintDescriptors of the data class are by default
            // only on the constructor unless specified with the @field:Annotation meta annotation which
            // would apply the annotation to the property's backing field instead of just its getter
            // method.
            Validation
                .buildDefaultValidatorFactory()
                .validator
                .validate(driver)
                .isEmpty() should be(true)

            val carWithInvalidNumberOfPassengers = TestClasses.Car(
                id = 1235,
                make = CarMake.Audi,
                model = "A4",
                year = 2010,
                owners = listOf(TestClasses.Person(id = "9999", name = "M Bailey", address = DefaultAddress)),
                licensePlate = "CA123",
                numDoors = 2,
                manual = true,
                ownershipStart = LocalDate.now(),
                ownershipEnd = LocalDate.now().plusYears(10),
                warrantyStart = LocalDate.now(),
                warrantyEnd = LocalDate.now().plusYears(3),
                passengers = emptyList()
            )
            val anotherDriver = TestClasses.Driver(
                person = TestClasses.Person(id = "55555", name = "K. Brown", address = DefaultAddress),
                car = carWithInvalidNumberOfPassengers
            )

            // ensure the class-level Car validation reports the correct property path of the field name
            assertViolations(
                obj = anotherDriver,
                withViolations = listOf(
                    WithViolation(
                        "car",
                        "number of passenger(s) is not valid",
                        carWithInvalidNumberOfPassengers,
                        TestClasses.Driver::class.java,
                        anotherDriver
                    )
                )
            )
        }

        test("DataClassValidator#class-level constraintDescriptors") {
            // The Car data class is annotated with @ValidPassengerCount which checks if the number of
            // passengers is greater than zero and less then some max specified in the annotation.
            // In this test, no passengers are defined so the validation fails the class-level constraint.
            val warrantyEnd = LocalDate.now().plusYears(3)
            val car = TestClasses.Car(
                id = 1234,
                make = CarMake.Volkswagen,
                model = "Beetle",
                year = 2004,
                owners = listOf(TestClasses.Person(id = "9999", name = "K. Ann", address = DefaultAddress)),
                licensePlate = "CA123",
                numDoors = 2,
                manual = true,
                ownershipStart = LocalDate.now(),
                ownershipEnd = LocalDate.now().plusYears(10),
                warrantyStart = null,
                warrantyEnd = warrantyEnd,
                passengers = emptyList()
            )

            assertViolations(
                obj = car,
                withViolations = listOf(
                    WithViolation("", "number of passenger(s) is not valid", car, TestClasses.Car::class.java, car),
                    WithViolation("validateId", "id may not be even", car, TestClasses.Car::class.java, car),
                    WithViolation(
                        "warrantyTimeValid.warrantyEnd",
                        "both warrantyStart and warrantyEnd are required for a valid range",
                        warrantyEnd,
                        TestClasses.Car::class.java,
                        car
                    ),
                    WithViolation(
                        "warrantyTimeValid.warrantyStart",
                        "both warrantyStart and warrantyEnd are required for a valid range",
                        null,
                        TestClasses.Car::class.java,
                        car
                    )
                )
            )

            // compare the class-level violation which should be the same as from the DataClassValidator
            val fromUnderlyingViolations = validator.underlying.validate(car).toSet()
            fromUnderlyingViolations.size should be(1)
            val violation = fromUnderlyingViolations.first()
            violation.propertyPath.toString() should be("")
            violation.message should be("number of passenger(s) is not valid")
            violation.rootBeanClass should be(TestClasses.Car::class.java)
            violation.invalidValue should be(car)
            violation.rootBean should be(car)

            // Validation with the default group does not return any violations with the standard
            // Hibernate Validator because the constraint constraintDescriptors of the data class are by default
            // only on the constructor unless specified with the @field:Annotation meta annotation.
            // However, class-level constraintDescriptors will work with the standard Hibernate Validator.
            val configuration = Validation
                .byDefaultProvider()
                .configure() as HibernateValidatorConfiguration
            val mapping = configuration.createConstraintMapping()
            mapping
                .constraintDefinition(ValidPassengerCount::class.java)
                .validatedBy(ValidPassengerCountConstraintValidator::class.java)
            configuration.addMapping(mapping)

            val hibernateViolations = configuration.buildValidatorFactory().validator
                .validate(car)

            // only has class-level constraint violation
            val classLevelConstraintViolation = hibernateViolations.first()
            classLevelConstraintViolation.propertyPath.toString() should be("")
            classLevelConstraintViolation.message should be("number of passenger(s) is not valid")
            classLevelConstraintViolation.rootBeanClass should be(TestClasses.Car::class.java)
            classLevelConstraintViolation.rootBean should be(car)
        }

        test("DataClassValidator#cascaded validations") {
            val validUser = TestClasses.User("1234567", "ion", "Other")
            val invalidUser = TestClasses.User("", "anion", "M")
            val nestedValidUser = TestClasses.Users(listOf(validUser))
            val nestedInvalidUser = TestClasses.Users(listOf(invalidUser))
            val nestedDuplicateUser = TestClasses.Users(listOf(validUser, validUser))

            assertViolations(obj = validUser)
            assertViolations(
                obj = invalidUser,
                withViolations = listOf(
                    WithViolation("id", "must not be empty", "", TestClasses.User::class.java, invalidUser)
                )
            )

            assertViolations(obj = nestedValidUser)
            assertViolations(
                obj = nestedInvalidUser,
                withViolations = listOf(
                    WithViolation(
                        "users[0].id",
                        "must not be empty",
                        "",
                        TestClasses.Users::class.java,
                        nestedInvalidUser
                    )
                )
            )

            assertViolations(obj = nestedDuplicateUser)
        }

        /*
         * Builder withDescriptorCacheSize(...) tests
         */
        test("DataClassValidator#withDescriptorCacheSize should override the default cache size") {
            val customizedCacheSize: Long = 512
            val dataClassValidator = DataClassValidator.builder()
                .withDescriptorCacheSize(customizedCacheSize)
            dataClassValidator.descriptorCacheSize should be(customizedCacheSize)
        }

        test("DataClassValidator#validate is valid") {
            val testUser = TestClasses.User(id = "9999", name = "April", gender = "F")
            validator.validate(testUser).isEmpty() should be(true)
        }

        test("DataClassValidator#validate returns valid result even when data class has other fields") {
            val testPerson = TestClasses.Person(id = "9999", name = "April", address = DefaultAddress)
            validator.validate(testPerson).isEmpty() should be(true)
        }

        test("DataClassValidator#validate is invalid") {
            val testUser = TestClasses.User(id = "", name = "April", gender = "F")
            val fieldAnnotation = getValidationAnnotations(TestClasses.User::class.java, "id").first()

            assertViolations(
                obj = testUser,
                withViolations = listOf(
                    WithViolation(
                        "id",
                        "must not be empty",
                        "",
                        TestClasses.User::class.java,
                        testUser,
                        fieldAnnotation
                    )
                )
            )
        }

        test(
            "DataClassValidator#validate returns invalid result of both field validations and post construct validations"
        ) {
            val testUser = TestClasses.User(id = "", name = "", gender = "Female")

            val idAnnotation: Annotation = getValidationAnnotations(TestClasses.User::class.java, "id").first()
            val genderAnnotation: Annotation = getValidationAnnotations(TestClasses.User::class.java, "gender").first()
            val methodAnnotation: Annotation =
                getValidationAnnotations(TestClasses.User::class.java, "nameCheck").first()

            assertViolations(
                obj = testUser,
                withViolations = listOf(
                    WithViolation(
                        "gender",
                        "Female not one of [F, M, Other]",
                        "Female",
                        TestClasses.User::class.java,
                        testUser,
                        genderAnnotation
                    ),
                    WithViolation("id", "must not be empty", "", TestClasses.User::class.java, testUser, idAnnotation),
                    WithViolation(
                        "nameCheck.name",
                        "cannot be empty",
                        "",
                        TestClasses.User::class.java,
                        testUser,
                        methodAnnotation
                    )
                )
            )
        }

        test("DataClassValidator#should register the user defined constraint validator") {
            val testState = TestClasses.StateValidationExample(state = "NY")
            val fieldAnnotation: Annotation =
                getValidationAnnotations(TestClasses.StateValidationExample::class.java, "state").first()

            assertViolations(
                obj = testState,
                withViolations = listOf(
                    WithViolation(
                        "state",
                        "Please register with state CA",
                        "NY",
                        TestClasses.StateValidationExample::class.java,
                        testState,
                        fieldAnnotation
                    )
                )
            )
        }

        test("DataClassValidator#secondary data class constructors") {
            // the framework does not validate on construction, however, it must use
            // the data class executable for finding the field validation constraintDescriptors.
            // Validations only apply to executable parameters that are also class fields,
            // e.g., from the primary executable.
            assertViolations(obj = TestClasses.TestJsonCreator("42"))
            assertViolations(obj = TestClasses.TestJsonCreator(42))
            assertViolations(obj = TestClasses.TestJsonCreator2(listOf("1", "2", "3")))
            assertViolations(
                obj = TestClasses.TestJsonCreator2(listOf(1, 2, 3), default = "Goodbye, world")
            )

            assertViolations(obj = TestClasses.TestJsonCreatorWithValidation("42"))
            assertThrows<NumberFormatException> {
                // can't validate after the fact -- the instance is already constructed, then we validate
                assertViolations(obj = TestClasses.TestJsonCreatorWithValidation(""))
            }
            assertViolations(obj = TestClasses.TestJsonCreatorWithValidation(42))

            // constraintDescriptors are on primary executable
            assertViolations(
                obj = TestClasses.TestJsonCreatorWithValidations("99"),
                withViolations = listOf(
                    WithViolation(
                        "int",
                        "99 not one of [42, 137]",
                        99,
                        TestClasses.TestJsonCreatorWithValidations::class.java,
                        TestClasses.TestJsonCreatorWithValidations("99")
                    )
                )
            )
            assertViolations(obj = TestClasses.TestJsonCreatorWithValidations(42))

            assertViolations(obj = TestClasses.DataClassWithMultipleConstructors("10001", "20002", "30003"))
            assertViolations(obj = TestClasses.DataClassWithMultipleConstructors(10001L, 20002L, 30003L))

            assertViolations(obj = TestClasses.DataClassWithMultipleConstructorsAnnotated(10001L, 20002L, 30003L))
            assertViolations(
                obj =
                TestClasses.DataClassWithMultipleConstructorsAnnotated(
                    "10001", "20002", "30003"
                )
            )

            assertViolations(
                obj = TestClasses.DataClassWithMultipleConstructorsAnnotatedAndValidations(
                    10001L,
                    20002L,
                    UUID.randomUUID().toString()
                )
            )

            assertViolations(
                obj = TestClasses.DataClassWithMultipleConstructorsAnnotatedAndValidations(
                    "10001",
                    "20002",
                    UUID.randomUUID().toString()
                )
            )

            val invalid = TestClasses.DataClassWithMultipleConstructorsPrimaryAnnotatedAndValidations(
                "9999",
                "10001",
                UUID.randomUUID().toString()
            )
            assertViolations(
                obj = invalid,
                withViolations = listOf(
                    WithViolation(
                        "number1",
                        "must be greater than or equal to 10000",
                        9999,
                        TestClasses.DataClassWithMultipleConstructorsPrimaryAnnotatedAndValidations::class.java,
                        invalid
                    )
                )
            )

            val d = TestClasses.InvalidDoublePerson("Andrea") { "" }
            assertViolations(d)
            // underlying also ignores function arguments
            validator.underlying.validate(d).size should be(0)
            val d2 = TestClasses.DoublePerson("Andrea") { "" }
            assertViolations(d2)
            // underlying also ignores function arguments
            validator.underlying.validate(d2).size should be(0)

            // verification fails here because of the post construct validation
            val d3 = TestClasses.ValidDoublePerson("Andrea") { "" }
            val ve = assertThrows<ValidationException> {
                validator.verify(d3)
            }
            ve.isInstanceOf<ConstraintViolationException>() should be(true)
            val cve = ve as ConstraintViolationException
            cve.constraintViolations.size shouldBeEqual 1
            cve.message should be(
                "\nValidation Errors:\t\tcheckOtherName: otherName must be longer than 3 chars\n\n"
            )

            // underlying still ignores function arguments
            validator.underlying.validate(d3).size should be(0)

            val d4 = TestClasses.PossiblyValidDoublePerson("Andrea") { "" }
            assertViolations(d4)
            // underlying also ignores function arguments
            validator.underlying.validate(d4).size should be(0)

            val d5 = TestClasses.WithFinalValField(
                UUID.randomUUID().toString(),
                "Joan",
                "Jett",
                setOf(1, 2, 3)
            )
            assertViolations(d5)
        }

        test("DataClassValidator#cycles") {
            validator.validate(TestClasses.A("5678", TestClasses.B("9876", TestClasses.C("444", null))))
                .isEmpty() should be(true)
            validator.validate(TestClasses.D("1", TestClasses.E("2", TestClasses.F("3", null)))).isEmpty() should be(
                true
            )
            validator.validate(TestClasses.G("1", TestClasses.H("2", TestClasses.I("3", emptyList()))))
                .isEmpty() should be(true)
        }

        test("DataClassValidator#java BeanProperty") {
            val value = TestClasses.AnnotatedBeanProperties(3)
            value.field1 = ""
            assertViolations(
                obj = value,
                withViolations = listOf(
                    WithViolation(
                        "field1",
                        "must not be empty",
                        "",
                        TestClasses.AnnotatedBeanProperties::class.java,
                        value
                    )
                )
            )
        }

        test("DataClassValidator#data class with no executable params") {
            val value = TestClasses.NoConstructorParams(null)
            value.id = "1234"
            assertViolations(value)

            assertViolations(
                obj = TestClasses.NoConstructorParams(null),
                withViolations = listOf(
                    WithViolation(
                        "id",
                        "must not be empty",
                        "",
                        TestClasses.NoConstructorParams::class.java,
                        TestClasses.NoConstructorParams(null)
                    )
                )
            )
        }

        test("DataClassValidator#data class annotated non executable field") {
            assertViolations(
                obj = TestClasses.AnnotatedInternalFields("1234", "thisisabigstring"),
                withViolations = listOf(
                    WithViolation(
                        "company",
                        "must not be empty",
                        "",
                        TestClasses.AnnotatedInternalFields::class.java,
                        TestClasses.AnnotatedInternalFields("1234", "thisisabigstring")
                    )
                )
            )
        }

        test("DataClassValidator#inherited validation constraintDescriptors") {
            assertViolations(
                obj = TestClasses.ImplementsAncestor(""),
                withViolations = listOf(
                    WithViolation(
                        "field1",
                        "must not be empty",
                        "",
                        TestClasses.ImplementsAncestor::class.java,
                        TestClasses.ImplementsAncestor("")
                    ),
                    WithViolation(
                        "validateField1.field1",
                        "not a double value",
                        "",
                        TestClasses.ImplementsAncestor::class.java,
                        TestClasses.ImplementsAncestor("")
                    )
                )
            )

            assertViolations(
                obj = TestClasses.ImplementsAncestor("blimey"),
                withViolations = listOf(
                    WithViolation(
                        "validateField1.field1",
                        "not a double value",
                        "blimey",
                        TestClasses.ImplementsAncestor::class.java,
                        TestClasses.ImplementsAncestor("blimey")
                    )
                )
            )

            assertViolations(obj = TestClasses.ImplementsAncestor("3.141592653589793d"))
        }

        test("DataClassValidator#getConstraintsForClass") {
            // there is no resolution of nested types in a description
            val driverDataClassDescriptor = validator.getConstraintsForClass(TestClasses.Driver::class.java)
            driverDataClassDescriptor.constrainedProperties.size should be(2)

            verifyDriverClassDescriptor(driverDataClassDescriptor)
        }

        test("DataClassValidator#constraintDescriptors") {
            val dataClassDescriptor = validator.descriptorFactory.describe(TestClasses.TestAnnotations::class.java)
            dataClassDescriptor.constrainedProperties.size should be(1)
            val propertyDescriptor = dataClassDescriptor.constrainedProperties.first()
            propertyDescriptor.propertyName should be("cars")
            propertyDescriptor.isInstanceOf<PropertyDescriptor>() should be(true)
            propertyDescriptor.constraintDescriptors.size should be(1)
            propertyDescriptor.constraintDescriptors.first().annotation.annotationClass should be(NotEmpty::class)
        }

        test("DataClassValidator#generics 1") {
            val dataClassDescriptor =
                validator.descriptorFactory.describe(TestClasses.GenericTestDataClass::class.java)
            dataClassDescriptor.constrainedProperties.size should be(1)
            val value = TestClasses.GenericTestDataClass(data = "Hello, World")
            val results = validator.validate(value)
            results.isEmpty() should be(true)

            assertThrows<UnexpectedTypeException> {
                validator.validate(TestClasses.GenericTestDataClass(3))
            }

            val genericValue = TestClasses.GenericTestDataClass(emptyList<String>())
            val violations = validator.validate(genericValue)
            violations.size should be(1)
            val violation = violations.first()
            violation.message should be("must not be empty")
            violation.propertyPath.toString() should be("data")
            violation.rootBeanClass should be(TestClasses.GenericTestDataClass::class.java)
            violation.leafBean should be(genericValue)
            violation.invalidValue should be(emptyList<String>())

            validator.validate(TestClasses.GenericMinTestDataClass(5)).isEmpty() should be(true)

            val dataClassDescriptor1 =
                validator.descriptorFactory.describe(TestClasses.GenericTestDataClassMultipleTypes::class.java)
            dataClassDescriptor1.constrainedProperties.size should be(3)

            val value1 = TestClasses.GenericTestDataClassMultipleTypes(
                data = null,
                things = listOf(1, 2, 3),
                otherThings = listOf(
                    TestClasses.UUIDExample("1234"),
                    TestClasses.UUIDExample(UUID.randomUUID().toString()),
                    TestClasses.UUIDExample(UUID.randomUUID().toString())
                )
            )
            val results1 = validator.validate(value1)
            results1.isEmpty() should be(true)
        }

        test("DataClassValidator#generics 2") {
            val value: TestClasses.Page<TestClasses.Person> = TestClasses.Page(
                listOf(
                    TestClasses.Person(id = "9999", name = "April", address = DefaultAddress),
                    TestClasses.Person(id = "9999", name = "April", address = DefaultAddress)
                ),
                0,
                null,
                null
            )

            val dataClassDescriptor =
                validator.descriptorFactory.describe(TestClasses.Page::class.java)
            dataClassDescriptor.constrainedProperties.size should be(1)

            val violations = validator.validate(value)
            violations.size should be(1)

            val violation = violations.first()
            violation.propertyPath.toString() should be("pageSize")
            violation.message should be("must be greater than or equal to 1")
            violation.rootBeanClass should be(TestClasses.Page::class.java)
            violation.rootBean should be(value)
            violation.invalidValue should be(0)
        }

        test("DataClassValidator#test boxed primitives") {
            validator
                .validateValue(
                    TestClasses.DataClassWithBoxedPrimitives::class.java,
                    "events",
                    Integer.valueOf(42)
                ).isEmpty() should be(true)
        }

        test("DataClassValidator#find executable 1") {
            /* DataClassWithMultipleConstructorsAnnotatedAndValidations */
            val kClazz = TestClasses.DataClassWithMultipleConstructorsAnnotatedAndValidations::class

            // by default this will find listOf(Long, Long, String)
            val constructorParameterTypes = kClazz.primaryConstructor!!.parameters.map { it.type.javaType }
            constructorParameterTypes.size should be(3)
            constructorParameterTypes[0] should be(Long::class.javaPrimitiveType)
            constructorParameterTypes[1] should be(Long::class.javaPrimitiveType)
            constructorParameterTypes[2] should be(String::class.java)

            val descriptor = validator.getConstraintsForClass(
                TestClasses.DataClassWithMultipleConstructorsAnnotatedAndValidations::class.java
            )
            descriptor.constrainedProperties.size should be(0) // no constrained constructors
        }

        test("DataClassValidator#find executable 2") {
            /* DataClassWithMultipleConstructorsPrimaryAnnotatedAndValidations */
            val kClazz = TestClasses.DataClassWithMultipleConstructorsPrimaryAnnotatedAndValidations::class

            // by default this will find listOf(Long, Long, String)
            val constructorParameterTypes = kClazz.primaryConstructor!!.parameters.map { it.type.javaType }
            constructorParameterTypes.size should be(3)
            constructorParameterTypes[0] should be(Long::class.javaPrimitiveType)
            constructorParameterTypes[1] should be(Long::class.javaPrimitiveType)
            constructorParameterTypes[2] should be(String::class.java)

            val descriptor = validator.getConstraintsForClass(
                TestClasses.DataClassWithMultipleConstructorsPrimaryAnnotatedAndValidations::class.java
            )
            descriptor.constrainedProperties.size should be(3)
            descriptor.getConstraintsForProperty("number1").constraintDescriptors.size should be(1)
            descriptor.getConstraintsForProperty("number2").constraintDescriptors.size should be(1)
            descriptor.getConstraintsForProperty("uuid").constraintDescriptors.size should be(1)
        }

        test("DataClassValidator#find executable 3") {
            /* DataClassWithMultipleConstructorsAnnotated */
            val kClazz = TestClasses.DataClassWithMultipleConstructorsAnnotated::class

            // this should find listOf(Long, Long, Long)
            val constructorParameterTypes = kClazz.primaryConstructor!!.parameters.map { it.type.javaType }
            constructorParameterTypes.size should be(3)
            constructorParameterTypes[0] should be(Long::class.javaPrimitiveType)
            constructorParameterTypes[1] should be(Long::class.javaPrimitiveType)
            constructorParameterTypes[2] should be(Long::class.javaPrimitiveType)

            val descriptor =
                validator.getConstraintsForClass(TestClasses.DataClassWithMultipleConstructorsAnnotated::class.java)
            descriptor.constrainedProperties.size should be(0) // no constrained constructors
        }

        test("DataClassValidator#find executable 4") {
            /* DataClassWithMultipleConstructors */
            val kClazz = TestClasses.DataClassWithMultipleConstructors::class

            // this should find listOf(Long, Long, Long)
            val constructorParameterTypes = kClazz.primaryConstructor!!.parameters.map { it.type.javaType }
            constructorParameterTypes.size should be(3)
            constructorParameterTypes[0] should be(Long::class.javaPrimitiveType)
            constructorParameterTypes[1] should be(Long::class.javaPrimitiveType)
            constructorParameterTypes[2] should be(Long::class.javaPrimitiveType)

            val descriptor =
                validator.getConstraintsForClass(TestClasses.DataClassWithMultipleConstructors::class.java)
            descriptor.constrainedProperties.size should be(0) // no constructors with any constraints
        }

        test("DataClassValidator#find executable 5") {
            /* TestJsonCreatorWithValidations */
            val kClazz = TestClasses.TestJsonCreatorWithValidations::class

            // should find listOf(int)
            val constructorParameterTypes = kClazz.primaryConstructor!!.parameters.map { it.type.javaType }
            constructorParameterTypes.size should be(1)
            constructorParameterTypes.first() should be(Int::class.javaPrimitiveType)

            val descriptor =
                validator.getConstraintsForClass(TestClasses.TestJsonCreatorWithValidations::class.java)
            descriptor.constrainedProperties.size should be(1)
            descriptor.getConstraintsForProperty("int").constraintDescriptors.size should be(1)
        }

        test("DataClassValidator#with Logging trait") {
            val value = TestClasses.PersonWithLogging(42, "Baz Var", null)
            validator.validate(value).isEmpty() should be(true)
        }

        test("DataClassValidator#class with incorrect post construct validation definition 1") {
            val value = TestClasses.WithIncorrectlyDefinedPostConstructValidation("abcd1234")

            val e = assertThrows<ConstraintDeclarationException> {
                validator.validate(value)
            }
            e.message should be(
                "Methods annotated with @PostConstructValidation must not declare any parameters, but method WithIncorrectlyDefinedPostConstructValidation#checkId(String) does."
            )
        }

        test("DataClassValidator#class with incorrect post construct validation definition 2") {
            val value = TestClasses.AnotherIncorrectlyDefinedPostConstructValidation("abcd1234")

            val e = assertThrows<ConstraintDeclarationException> {
                validator.validate(value)
            }
            e.message should be(
                "Methods annotated with @PostConstructValidation must return a PostConstructValidationResult, but method AnotherIncorrectlyDefinedPostConstructValidation#checkId() does not."
            )
        }

        test("Comparison") {
            val descriptorFromUnderlyingValidator =
                validator.underlying.getConstraintsForClass(JavaTestClass::class.java)
            val descriptor = validator.getConstraintsForClass(JavaTestClass::class.java)
            compareConstructorDescriptors(
                descriptor.constrainedConstructors,
                descriptorFromUnderlyingValidator.constrainedConstructors
            )

            descriptor.hasConstraints() should be(descriptorFromUnderlyingValidator.hasConstraints())
            descriptor.elementClass shouldBeEqual descriptorFromUnderlyingValidator.elementClass
            descriptor.constrainedProperties.size shouldBeEqual descriptorFromUnderlyingValidator.constrainedProperties.size
            descriptor.constraintDescriptors shouldBeEqual descriptorFromUnderlyingValidator.constraintDescriptors
            comparePropertyDescriptors(descriptor.constrainedProperties, descriptorFromUnderlyingValidator)
            compareMethodDescriptors(
                descriptor.getConstraintsForMethod("names"),
                descriptorFromUnderlyingValidator.getConstraintsForMethod("names")
            )

            val simpleClazz = validator.getConstraintsForClass(TestClasses.SimpleClass::class.java)
            val simpleClazzFromUnderlyingValidator =
                validator.underlying.getConstraintsForClass(TestClasses.SimpleClass::class.java)
            compareConstructorDescriptors(
                simpleClazz.constrainedConstructors,
                simpleClazzFromUnderlyingValidator.constrainedConstructors
            )

            simpleClazz.hasConstraints() shouldBeEqual simpleClazzFromUnderlyingValidator.hasConstraints()
            simpleClazz.elementClass shouldBeEqual simpleClazzFromUnderlyingValidator.elementClass
            simpleClazz.constrainedProperties.size shouldBeEqual simpleClazzFromUnderlyingValidator.constrainedProperties.size
            simpleClazz.constraintDescriptors shouldBeEqual simpleClazzFromUnderlyingValidator.constraintDescriptors
            comparePropertyDescriptors(simpleClazz.constrainedProperties, simpleClazzFromUnderlyingValidator)
        }
    }

    private fun compareMethodDescriptors(first: MethodDescriptor, second: MethodDescriptor) {
        first.name shouldBeEqual second.name
        first.hasConstraints() shouldBeEqual second.hasConstraints()
        first.hasConstrainedParameters() shouldBeEqual second.hasConstrainedParameters()
        first.constraintDescriptors shouldBeEqual second.constraintDescriptors
        first.elementClass shouldBeEqual second.elementClass
        first.crossParameterDescriptor.elementClass shouldBeEqual second.crossParameterDescriptor.elementClass
        compareReturnValueDescriptors(first.returnValueDescriptor, second.returnValueDescriptor)

        first.parameterDescriptors.size shouldBeEqual second.parameterDescriptors.size
        val size = first.parameterDescriptors.size
        var index = 0
        while (index < size) {
            val firstParameter = first.parameterDescriptors[index]
            val secondParameter = second.parameterDescriptors[index]

            firstParameter.name shouldBeEqual secondParameter.name
            firstParameter.elementClass shouldBeEqual secondParameter.elementClass
            firstParameter.constraintDescriptors shouldBeEqual secondParameter.constraintDescriptors
            firstParameter.index shouldBeEqual secondParameter.index
            firstParameter.isCascaded shouldBeEqual secondParameter.isCascaded
            firstParameter.hasConstraints() shouldBeEqual secondParameter.hasConstraints()
            compareConstrainedContainerElementTypeSets(
                firstParameter.constrainedContainerElementTypes,
                secondParameter.constrainedContainerElementTypes
            )

            index += 1
        }
    }

    private fun compareConstructorDescriptors(first: Set<ConstructorDescriptor>, second: Set<ConstructorDescriptor>) {
        first.size shouldBeEqual second.size
        val iterator = first.iterator()
        val secondIterator = second.iterator()
        while (iterator.hasNext()) {
            val firstOne = iterator.next()
            val secondOne = secondIterator.next()

            firstOne.name shouldBeEqual secondOne.name
            firstOne.elementClass shouldBeEqual secondOne.elementClass
            firstOne.hasConstraints() shouldBeEqual secondOne.hasConstraints()
            firstOne.hasConstrainedParameters() shouldBeEqual secondOne.hasConstrainedParameters()
            compareReturnValueDescriptors(firstOne.returnValueDescriptor, secondOne.returnValueDescriptor)
        }
    }

    private fun compareReturnValueDescriptors(first: ReturnValueDescriptor, second: ReturnValueDescriptor) {
        first.elementClass shouldBeEqual second.elementClass
        first.isCascaded shouldBeEqual second.isCascaded
        first.constrainedContainerElementTypes
        first.constraintDescriptors shouldBeEqual second.constraintDescriptors
        compareConstrainedContainerElementTypeSets(
            first.constrainedContainerElementTypes,
            second.constrainedContainerElementTypes
        )
    }

    private fun comparePropertyDescriptors(first: Set<PropertyDescriptor>, second: BeanDescriptor) {
        first.map { propertyDescriptor ->
            val fromUnderlying = second.getConstraintsForProperty(propertyDescriptor.propertyName)
            fromUnderlying shouldNot beNull()
            propertyDescriptor.elementClass shouldBeEqual fromUnderlying.elementClass
            propertyDescriptor.isCascaded shouldBeEqual fromUnderlying.isCascaded
            propertyDescriptor.constraintDescriptors shouldBeEqual fromUnderlying.constraintDescriptors
            propertyDescriptor.constrainedContainerElementTypes.size shouldBeEqual fromUnderlying.constrainedContainerElementTypes.size

            compareConstrainedContainerElementTypes(
                propertyDescriptor.constrainedContainerElementTypes.first(),
                fromUnderlying.constrainedContainerElementTypes.first()
            )
        }
    }

    private fun compareConstrainedContainerElementTypeSets(
        first: Set<ContainerElementTypeDescriptor>,
        second: Set<ContainerElementTypeDescriptor>
    ) {
        first.size shouldBeEqual second.size
        val iterator = first.iterator()
        val secondIterator = second.iterator()
        while (iterator.hasNext()) {
            compareConstrainedContainerElementTypes(iterator.next(), secondIterator.next())
        }
    }

    private fun compareConstrainedContainerElementTypes(
        first: ContainerElementTypeDescriptor,
        second: ContainerElementTypeDescriptor
    ) {
        first.elementClass shouldBeEqual second.elementClass
        first.containerClass shouldBeEqual second.containerClass
        first.typeArgumentIndex shouldBeEqual second.typeArgumentIndex
        first.isCascaded shouldBeEqual second.isCascaded
        first.constraintDescriptors shouldBeEqual second.constraintDescriptors
        first.constrainedContainerElementTypes.size shouldBeEqual second.constrainedContainerElementTypes.size
    }

    @Suppress("UNCHECKED_CAST", "PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private fun newJavaSet(numElements: Int): java.util.Set<Int> {
        val result = HashSet<Int>()
        for (i in 1..numElements) {
            result.add(i)
        }
        return result as java.util.Set<Int>
    }

//    private fun findConstrainedProperty(descriptor: BeanDescriptor, propertyName: String): PropertyDescriptor {
//        val propertyDescriptor = descriptor.constrainedProperties.find { it.propertyName == propertyName }
//        assert(propertyDescriptor != null)
//        return propertyDescriptor!!
//    }

    private fun verifyDriverClassDescriptor(/* TestClasses.Driver::class.java */ descriptor: BeanDescriptor) {
        val personConstrainedPropertyDescriptor = descriptor.constrainedProperties.find { it.propertyName == "person" }
        personConstrainedPropertyDescriptor shouldNot beNull()
        personConstrainedPropertyDescriptor!!.elementClass should be(TestClasses.Person::class.java)
        personConstrainedPropertyDescriptor.constraintDescriptors.isEmpty() should be(true) // no constraints, just the @Valid annotation for cascading
        personConstrainedPropertyDescriptor.isCascaded should be(true)

        // person is cascaded, so let's look it up
        val personBeanDescriptor = validator.getConstraintsForClass(TestClasses.Person::class.java)
        personBeanDescriptor.constrainedProperties.size should be(3)

        val id = personBeanDescriptor.getConstraintsForProperty("id")
        id.elementClass should be(String::class.java)
        id.constraintDescriptors.size should be(1)
        id.constraintDescriptors.first().annotation.annotationClass should be(NotEmpty::class)
        id.isCascaded should be(false)

        val name = personBeanDescriptor.getConstraintsForProperty("name")
        name.elementClass should be(String::class.java)
        name.constraintDescriptors.size should be(1)
        name.constraintDescriptors.first().annotation.annotationClass should be(NotEmpty::class)
        name.isCascaded should be(false)

        val address = personBeanDescriptor.getConstraintsForProperty("address")
        address.elementClass should be(TestClasses.Address::class.java)
        address.constraintDescriptors.isEmpty() should be(true)
        address.isCascaded should be(true)

        // address is cascaded, so let's look it up
        val addressDescriptor = validator.getConstraintsForClass(TestClasses.Address::class.java)
        addressDescriptor.constrainedProperties.size should be(1)

        val addressState = addressDescriptor.getConstraintsForProperty("state")
        addressState.elementClass should be(String::class.java)
        addressState.constraintDescriptors.size should be(1)
        addressState.constraintDescriptors.first().annotation.annotationClass should be(StateConstraint::class)
        addressState.isCascaded should be(false)

        val validateAddressMethodDescriptor = personBeanDescriptor.getConstraintsForMethod("validateAddress")
        validateAddressMethodDescriptor shouldNot beNull()
        validateAddressMethodDescriptor.constraintDescriptors.size shouldBeEqual 0 //@PostConstructValidation should be on the Return Value
        validateAddressMethodDescriptor.returnValueDescriptor.constraintDescriptors.size shouldBeEqual 1
        validateAddressMethodDescriptor
            .returnValueDescriptor
            .constraintDescriptors
            .first()
            .annotation
            .annotationClass should be(PostConstructValidation::class)


        val car = descriptor.getConstraintsForProperty("car")
        car.elementClass should be(TestClasses.Car::class.java)
        car.constraintDescriptors.isEmpty() should be(true)
        car.isCascaded should be(true)

        // car is cascaded, so let's look it up
        val carDescriptor = validator.getConstraintsForClass(TestClasses.Car::class.java)
        carDescriptor.constrainedProperties.size should be(5)

        // just look at annotated and cascaded fields in car
        val year = carDescriptor.getConstraintsForProperty("year")
        year.elementClass should be(Int::class.javaPrimitiveType)
        year.constraintDescriptors.size should be(1)
        year.constraintDescriptors.first().annotation.annotationClass should be(Min::class)
        year.isCascaded should be(false)

        val owners = carDescriptor.getConstraintsForProperty("owners")
        owners.elementClass should be(
            List::class.createType(
                arguments =
                listOf(
                    KTypeProjection(
                        KVariance.INVARIANT,
                        TestClasses.Person::class.createType()
                    )
                )
            ).jvmErasure.java
        )
        owners.constraintDescriptors.isEmpty() should be(true)
        owners.isCascaded should be(true)

        val licensePlate = carDescriptor.getConstraintsForProperty("licensePlate")
        licensePlate.elementClass should be(String::class.java)
        licensePlate.constraintDescriptors.size should be(2)
        licensePlate.constraintDescriptors.first().annotation.annotationClass should be(NotEmpty::class)
        licensePlate.constraintDescriptors.last().annotation.annotationClass should be(Size::class)
        licensePlate.isCascaded should be(false)

        val numDoors = carDescriptor.getConstraintsForProperty("numDoors")
        numDoors.elementClass should be(Int::class.javaPrimitiveType)
        numDoors.constraintDescriptors.size should be(1)
        numDoors.constraintDescriptors.first().annotation.annotationClass should be(Min::class)
        numDoors.isCascaded should be(false)

        val passengers = carDescriptor.getConstraintsForProperty("passengers")
        passengers.constraintDescriptors.isEmpty() should be(true)
        passengers.elementClass should be(
            List::class.createType(
                listOf(
                    KTypeProjection(
                        KVariance.INVARIANT,
                        TestClasses.Person::class.createType()
                    )
                )
            ).jvmErasure.java
        )
        passengers.isCascaded should be(true) // because of a workaround both the parameter and the constrained container type are marked as cascade.
        passengers.constrainedContainerElementTypes.isEmpty() should be(false)
        passengers.constrainedContainerElementTypes.size shouldBeEqual 1
        passengers.constrainedContainerElementTypes.first().elementClass should be(TestClasses.Person::class.java)
        passengers.constrainedContainerElementTypes.first().isCascaded should be(true)
        passengers.constraintDescriptors.isEmpty() should be(true)
        passengers.isCascaded should be(true)

        val validateId = carDescriptor.getConstraintsForMethod("validateId")
        validateId shouldNot beNull()
        validateId.constraintDescriptors.size shouldBeEqual 0
        validateId.returnValueDescriptor.constraintDescriptors.size shouldBeEqual 1
        validateId.returnValueDescriptor.constraintDescriptors.first().annotation.annotationClass should be(
            PostConstructValidation::class
        )

        val validateYearBeforeNow = carDescriptor.getConstraintsForMethod("validateYearBeforeNow")
        validateYearBeforeNow shouldNot beNull()
        validateYearBeforeNow.constraintDescriptors.size shouldBeEqual 0
        validateYearBeforeNow.returnValueDescriptor.constraintDescriptors.size shouldBeEqual 1
        validateYearBeforeNow.returnValueDescriptor.constraintDescriptors.first().annotation.annotationClass should be(
            PostConstructValidation::class
        )

        val ownershipTimesValid = carDescriptor.getConstraintsForMethod("ownershipTimesValid")
        ownershipTimesValid shouldNot beNull()
        ownershipTimesValid.constraintDescriptors.size shouldBeEqual 0
        ownershipTimesValid.returnValueDescriptor.constraintDescriptors.size shouldBeEqual 1
        ownershipTimesValid.returnValueDescriptor.constraintDescriptors.first().annotation.annotationClass should be(
            PostConstructValidation::class
        )

        val warrantyTimeValid = carDescriptor.getConstraintsForMethod("warrantyTimeValid")
        warrantyTimeValid shouldNot beNull()
        warrantyTimeValid.constraintDescriptors.size shouldBeEqual 0
        warrantyTimeValid.returnValueDescriptor.constraintDescriptors.size shouldBeEqual 1
        warrantyTimeValid.returnValueDescriptor.constraintDescriptors.first().annotation.annotationClass should be(
            PostConstructValidation::class
        )
    }

    private fun getValidationAnnotations(clazz: Class<*>, name: String): List<Annotation> {
        val descriptor = validator.descriptorFactory.describe(clazz)
        return when (val property = descriptor.getConstraintsForProperty(name)) {
            null -> {
                var method = descriptor.getConstraintsForMethod(name)
                if (method == null) {
                    method = descriptor.getConstraintsForMethod("get" + name.replaceFirstChar { it.uppercase() })
                }
                when (method) {
                    null -> emptyList()
                    else -> {
                        method
                            .constraintDescriptors
                            .map { it.annotation } +
                                method
                                    .returnValueDescriptor
                                    .constraintDescriptors
                                    .map { it.annotation }
                    }
                }
            }

            else -> property.constraintDescriptors.map { it.annotation }
        }
    }
}
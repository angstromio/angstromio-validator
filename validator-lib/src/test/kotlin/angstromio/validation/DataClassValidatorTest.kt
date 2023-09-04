package angstromio.validation

import angstromio.util.extensions.Anys.isInstanceOf
import angstromio.validation.cfg.ConstraintMapping
import angstromio.validation.constraints.CountryCode
import angstromio.validation.constraints.ISO3166CountryCodeConstraintValidator
import angstromio.validation.constraints.InvalidConstraint
import angstromio.validation.constraints.InvalidConstraintValidator
import angstromio.validation.constraints.NotEmptyAnyConstraintValidator
import angstromio.validation.constraints.NotEmptyPathConstraintValidator
import angstromio.validation.constraints.StateConstraint
import angstromio.validation.constraints.StateConstraintPayload
import angstromio.validation.constraints.ValidPassengerCount
import angstromio.validation.constraints.ValidPassengerCountConstraintValidator
import angstromio.validation.extensions.getDynamicPayload
import angstromio.validation.metadata.DataClassDescriptor
import angstromio.validation.metadata.PropertyDescriptor
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
import org.hibernate.validator.HibernateValidatorConfiguration
import org.hibernate.validator.internal.util.annotation.AnnotationDescriptor
import org.hibernate.validator.internal.util.annotation.AnnotationFactory
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate
import java.util.*
import kotlin.reflect.KClass
import kotlin.reflect.KTypeProjection
import kotlin.reflect.KVariance
import kotlin.reflect.full.createType
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.javaType

@OptIn(ExperimentalStdlibApi::class)
class DataClassValidatorTest : AssertViolationTest() {
    companion object {
        val DefaultAddress: testclasses.Address =
            testclasses.Address(line1 = "1234 Main St", city = "Anywhere", state = "CA", zipcode = "94102")

        val CustomConstraintMappings: Set<ConstraintMapping> =
            setOf(
                ConstraintMapping(
                    ValidPassengerCount::class,
                    ValidPassengerCountConstraintValidator::class,
                    includeExistingValidators = false
                ),
                ConstraintMapping(
                    InvalidConstraint::class,
                    InvalidConstraintValidator::class
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
                val car = testclasses.SmallCar(null, "DD-AB-123", 4)
                val violations = withCustomMessageInterpolator.validate(car)
                assertViolations(
                    violations = violations,
                    withViolations = listOf(
                        WithViolation(
                            "manufacturer",
                            "Whatever you entered was wrong",
                            null,
                            testclasses.SmallCar::class.java,
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
            val value = testclasses.UnicodeNameDataClass(5, "")

            assertViolations(
                obj = value,
                withViolations = listOf(
                    WithViolation(
                        "name",
                        "must not be empty",
                        "",
                        testclasses.UnicodeNameDataClass::class.java,
                        value,
                        getValidationAnnotations(testclasses.UnicodeNameDataClass::class, "name").last()
                    ),
                    WithViolation(
                        "winning-id",
                        "must be less than or equal to 3",
                        5,
                        testclasses.UnicodeNameDataClass::class.java,
                        value,
                        getValidationAnnotations(testclasses.UnicodeNameDataClass::class, "winning-id").last()
                    ),
                )
            )
        }

        test("DataClassValidator#correctly handle not cascading for a non-data class type") {
            val value = testclasses.WithBogusCascadedField("DD-AB-123", listOf(1, 2, 3))
            assertViolations(value)
        }

        test("DataClassValidator#with InvalidConstraint") {
            val value = testclasses.AlwaysFails("fails")

            val e = assertThrows<RuntimeException> {
                validator.validate(value)
            }
            e.message should be("FORCED TEST EXCEPTION")
        }

        test("DataClassValidator#with failing MethodValidation") {
            // fails the @NotEmpty on the id field but the method validation blows up
            val value = testclasses.AlwaysFailsMethodValidation("")

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
            val value = testclasses.PathNotEmpty(testclasses.TestPath.Empty, "abcd1234")

            // no validator registered `@NotEmpty` for Path type
            assertThrows<UnexpectedTypeException> {
                validator.validate(value)
            }

            // define an additional constraint validator for `@NotEmpty` which works for
            // Path types. note we include all existing validators
            val pathCustomConstraintMapping =
                ConstraintMapping(NotEmpty::class, NotEmptyPathConstraintValidator::class)

            val withPathConstraintValidator: DataClassValidator =
                DataClassValidator
                    .builder()
                    .withConstraintMapping(pathCustomConstraintMapping).validator()

            try {
                val violations = withPathConstraintValidator.validate(value)
                violations.size should be(1)
                violations.first().propertyPath.toString() should be("path")
                violations.first().message should be("must not be empty")
                violations.first().rootBeanClass should be(testclasses.PathNotEmpty::class.java)
                violations.first().rootBean should be(value)
                violations.first().invalidValue should be(testclasses.TestPath.Empty)

                // valid instance
                withPathConstraintValidator
                    .validate(testclasses.PathNotEmpty(testclasses.TestPath("node"), "abcd1234"))
                    .isEmpty() should be(true)
            } finally {
                withPathConstraintValidator.close()
            }

            // define an additional constraint validator for `@NotEmpty` which works for
            // Path types. note we do include all existing validators which will cause validation to
            // fail since we can no longer find a validator for `@NotEmpty` which works for string
            val pathCustomConstraintMapping2 =
                ConstraintMapping(
                    NotEmpty::class,
                    NotEmptyPathConstraintValidator::class,
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
                ConstraintMapping(NotEmpty::class, NotEmptyPathConstraintValidator::class)
            val pathCustomConstraintMapping2 =
                ConstraintMapping(NotEmpty::class, NotEmptyAnyConstraintValidator::class)

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
                ConstraintMapping(NotEmpty::class, NotEmptyPathConstraintValidator::class)
            val pathCustomConstraintMapping4 =
                ConstraintMapping(
                    NotEmpty::class,
                    NotEmptyAnyConstraintValidator::class,
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
                    NotEmpty::class,
                    NotEmptyPathConstraintValidator::class,
                    includeExistingValidators = false
                )
            val pathCustomConstraintMapping6 =
                ConstraintMapping(NotEmpty::class, NotEmptyAnyConstraintValidator::class)

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
                    NotEmpty::class,
                    NotEmptyPathConstraintValidator::class,
                    includeExistingValidators = false
                )
            val pathCustomConstraintMapping8 =
                ConstraintMapping(
                    NotEmpty::class,
                    NotEmptyAnyConstraintValidator::class,
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
                testclasses.Address(line1 = "1234 Main St", city = "Anywhere", state = "PA", zipcode = "94102")
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

        test("DataClassValidator#method validation payload") {
            val user = testclasses.NestedUser(
                id = "abcd1234",
                person = testclasses.Person("abcd1234", "R. Franklin", DefaultAddress),
                gender = "F",
                job = ""
            )
            val violations = validator.validate(user)
            violations.size shouldBeEqual 1
            val violation = violations.first()
            val payload = violation.getDynamicPayload(testclasses.NestedUserPayload::class.java)
            assert(payload != null)
            payload?.let {
                testclasses.NestedUserPayload::class.java.isAssignableFrom(payload::class.java) should be(true)
                payload should be(testclasses.NestedUserPayload("abcd1234", ""))
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
            val validators = validator.findConstraintValidators(CountryCode::class)
            validators.size shouldBeEqual 1
            validators.first()::class.java.name should be(
                ISO3166CountryCodeConstraintValidator::class.java.name
            )

            (validators.first() as ConstraintValidator<CountryCode, String>).isValid("US", null) should be(true)
        }

        test("DataClassValidator#nulls") {
            val car = testclasses.SmallCar(null, "DD-AB-123", 4)
            assertViolations(
                obj = car,
                withViolations = listOf(
                    WithViolation("manufacturer", "must not be null", null, testclasses.SmallCar::class.java, car)
                )
            )
        }

        test("DataClassValidator#not a data class") {
            val value = testclasses.NotADataClass(null, "DD-AB-123", 4, "NotAUUID")
            val e = assertThrows<ValidationException> {
                validator.validate(value)
            }
            e.message should be("${testclasses.NotADataClass::class.java} is not a valid data class.")

            // underlying validator doesn't find the annotations (either executable nor inherited)
            val violations = validator.underlying.validate(value).toSet()
            violations.isEmpty() should be(true)
        }

        test("DataClassValidator#fails with @set meta annotation") {
            val value = testclasses.WithMetaSetAnnotation("")

            assertThrows<ConstraintDeclarationException> { assertViolations(obj = value, withViolations = emptyList()) }
            // this also fails with underlying validator
            assertThrows<ConstraintDeclarationException> { validator.underlying.validate(value).toSet() }

            val cascadingValue = testclasses.WithCascadingSetAnnotation(emptyList())
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
            val value = testclasses.WithMetaGetAnnotation("")

            // getter field annotations should work
            assertViolations(
                obj = value,
                withViolations = listOf(
                    WithViolation(
                        "id",
                        "must not be empty",
                        "",
                        testclasses.WithMetaGetAnnotation::class.java,
                        value,
                        getValidationAnnotations(testclasses.WithMetaGetAnnotation::class, "id").first()
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
            violation.rootBeanClass should be(testclasses.WithMetaGetAnnotation::class.java)
            violation.rootBean should be(value)
            violation.leafBean should be(value)
        }

        test("DataClassValidator#works with @field meta annotation") {
            val value = testclasses.WithMetaFieldAnnotation("")

            assertViolations(
                obj = value,
                withViolations = listOf(
                    WithViolation(
                        "id",
                        "must not be empty",
                        "",
                        testclasses.WithMetaFieldAnnotation::class.java,
                        value,
                        getValidationAnnotations(testclasses.WithMetaFieldAnnotation::class, "id").first()
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
            violation.rootBeanClass should be(testclasses.WithMetaFieldAnnotation::class.java)
            violation.rootBean should be(value)
            violation.leafBean should be(value)
        }

        test("DataClassValidator#works with @field meta annotation 1") {
            val value = testclasses.WithPartialMetaFieldAnnotation("")

            assertViolations(
                obj = value,
                withViolations = listOf(
                    WithViolation(
                        "id",
                        "must not be empty",
                        "",
                        testclasses.WithPartialMetaFieldAnnotation::class.java,
                        value,
                        getValidationAnnotations(testclasses.WithPartialMetaFieldAnnotation::class, "id").last()
                    ),
                    WithViolation(
                        "id",
                        "size must be between 2 and 10",
                        "",
                        testclasses.WithPartialMetaFieldAnnotation::class.java,
                        value,
                        getValidationAnnotations(testclasses.WithPartialMetaFieldAnnotation::class, "id").first()
                    )
                )
            )
        }

        test("DataClassValidator#validateValue 1") {
            val violations = validator.validateValue(testclasses.OneOfListExample::class, "enumValue", listOf("g"))
            violations.size shouldBeEqual 1
            val violation = violations.first()
            violation.message should be("g not one of [a, B, c]")
            violation.propertyPath.toString() should be("enumValue")
            violation.invalidValue should be(listOf("g"))
            violation.rootBeanClass should be(testclasses.OneOfListExample::class.java)
            violation.rootBean should beNull()
            violation.leafBean should beNull()
        }

        test("DataClassValidator#validateValue 2") {
            val descriptor = validator.getConstraintsForClass(testclasses.OneOfListExample::class)
            val violations = validator.validateValue(descriptor, "enumValue", listOf("g"))
            violations.size shouldBeEqual 1
            val violation = violations.first()
            violation.message should be("g not one of [a, B, c]")
            violation.propertyPath.toString() should be("enumValue")
            violation.invalidValue should be(listOf("g"))
            violation.rootBeanClass should be(testclasses.OneOfListExample::class.java)
            violation.rootBean should beNull()
            violation.leafBean should beNull()
        }

        test("DataClassValidator#validateProperty 1") {
            val value = testclasses.CustomerAccount("", null)
            val violations = validator.validateProperty(value, "accountName")
            violations.size should be(1)
            val violation = violations.first()
            violation.message should be("must not be empty")
            violation.propertyPath.toString() should be("accountName")
            violation.invalidValue should be("")
            violation.rootBeanClass should be(testclasses.CustomerAccount::class.java)
            violation.rootBean should be(value)
            violation.leafBean should be(value)
        }

        test("DataClassValidator#validateProperty 2") {
            validator
                .validateProperty(
                    testclasses.MinIntExample(numberValue = 2),
                    "numberValue"
                ).isEmpty() should be(true)
            assertViolations(obj = testclasses.MinIntExample(numberValue = 2))

            val invalid = testclasses.MinIntExample(numberValue = 0)
            val violations = validator
                .validateProperty(
                    invalid,
                    "numberValue"
                )
            violations.size shouldBeEqual 1
            val violation = violations.first()
            violation.propertyPath.toString() should be("numberValue")
            violation.message should be("must be greater than or equal to 1")
            violation.rootBeanClass should be(testclasses.MinIntExample::class.java)
            violation.rootBean.javaClass.name should be(invalid.javaClass.name)

            assertViolations(
                obj = invalid,
                withViolations = listOf(
                    WithViolation(
                        "numberValue",
                        "must be greater than or equal to 1",
                        0,
                        testclasses.MinIntExample::class.java,
                        invalid,
                        getValidationAnnotations(testclasses.MinIntExample::class, "numberValue").first()
                    )
                )
            )
        }

        test("DataClassValidator#validateFieldValue") {
            val constraints: Map<KClass<out Annotation>, Map<String, Any>> =
                mapOf(jakarta.validation.constraints.Size::class to mapOf("min" to 5, "max" to 7))

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

            val constraintsWithGroup: Map<KClass<out Annotation>, Map<String, Any>> =
                mapOf(
                    jakarta.validation.constraints.Size::class to mapOf(
                        "min" to 5,
                        "max" to 7,
                        "groups" to arrayOf(testclasses.PersonCheck::class.java)
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
                    testclasses.PersonCheck::class
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
                    constraints = mapOf(jakarta.validation.constraints.NotEmpty::class to emptyMap()),
                    fieldName = "data",
                    value = "Hello, world"
                ).isEmpty() should be(true)
        }

        test("DataClassValidator#groups support") {
            // see: https://docs.jboss.org/hibernate/stable/dataClassValidator/reference/en-US/html_single/#chapter-groups
            val toTest = testclasses.WithPersonCheck("", "Jane Doe")
            // PersonCheck group not enabled, no violations
            assertViolations(obj = toTest)
            // Completely diff group enabled, no violations
            validator.validate(obj = toTest, testclasses.OtherCheck::class)
            // PersonCheck group enabled
            assertViolations(
                obj = toTest,
                groups = listOf(testclasses.PersonCheck::class),
                withViolations = listOf(
                    WithViolation("id", "must not be empty", "", testclasses.WithPersonCheck::class.java, toTest)
                )
            )

            // multiple groups with PersonCheck group enabled
            assertViolations(
                obj = toTest,
                groups = listOf(testclasses.OtherCheck::class, testclasses.PersonCheck::class),
                withViolations = listOf(
                    WithViolation("id", "must not be empty", "", testclasses.WithPersonCheck::class.java, toTest)
                )
            )
        }

        test("DataClassValidator#isCascaded method validation - defined fields") {
            // nested method validation fields
            val owner = testclasses.Person(id = "", name = "A. Einstein", address = DefaultAddress)
            val beetleOwners = listOf(testclasses.Person(id = "9999", name = "", address = DefaultAddress))
            val beetlePassengers =
                listOf(testclasses.Person(id = "1001", name = "R. Franklin", address = DefaultAddress))
            val beetleOwnershipStart = LocalDate.now()
            val beetleOwnershipEnd = beetleOwnershipStart.plusYears(10)
            val beetleWarrantyEnd = beetleOwnershipStart.plusYears(3)
            val vwCarMake = CarMake.Volkswagen
            val car = testclasses.Car(
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
            val driver = testclasses.Driver(
                person = owner,
                car = car
            )

            // no PersonCheck -- doesn't fail
            assertViolations(obj = driver, groups = listOf(testclasses.PersonCheck::class))
            // with default group - fail
            assertViolations(
                obj = driver,
                withViolations = listOf(
                    WithViolation(
                        "car.owners[0].name",
                        "must not be empty",
                        "",
                        testclasses.Driver::class.java,
                        driver
                    ),
                    WithViolation("car.validateId", "id may not be even", car, testclasses.Driver::class.java, driver),
                    WithViolation(
                        "car.warrantyTimeValid.warrantyEnd",
                        "both warrantyStart and warrantyEnd are required for a valid range",
                        car,
                        testclasses.Driver::class.java,
                        driver
                    ),
                    WithViolation(
                        "car.warrantyTimeValid.warrantyStart",
                        "both warrantyStart and warrantyEnd are required for a valid range",
                        car,
                        testclasses.Driver::class.java,
                        driver
                    ),
                    WithViolation(
                        "car.year",
                        "must be greater than or equal to 2000",
                        1970,
                        testclasses.Driver::class.java,
                        driver
                    ),
                    WithViolation("person.id", "must not be empty", "", testclasses.Driver::class.java, driver)
                )
            )

            // Validation with the default group does not return any violations with the standard
            // Hibernate Validator because the constraint annotations of the data class are by default
            // only on the constructor unless specified with the @field:Annotation meta annotation which
            // would apply the annotation to the property's backing field instead of just its getter
            // method.
            Validation
                .buildDefaultValidatorFactory()
                .validator
                .validate(driver)
                .isEmpty() should be(true)

            val carWithInvalidNumberOfPassengers = testclasses.Car(
                id = 1235,
                make = CarMake.Audi,
                model = "A4",
                year = 2010,
                owners = listOf(testclasses.Person(id = "9999", name = "M Bailey", address = DefaultAddress)),
                licensePlate = "CA123",
                numDoors = 2,
                manual = true,
                ownershipStart = LocalDate.now(),
                ownershipEnd = LocalDate.now().plusYears(10),
                warrantyStart = LocalDate.now(),
                warrantyEnd = LocalDate.now().plusYears(3),
                passengers = emptyList()
            )
            val anotherDriver = testclasses.Driver(
                person = testclasses.Person(id = "55555", name = "K. Brown", address = DefaultAddress),
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
                        testclasses.Driver::class.java,
                        anotherDriver
                    )
                )
            )
        }

        test("DataClassValidator#class-level annotations") {
            // The Car data class is annotated with @ValidPassengerCount which checks if the number of
            // passengers is greater than zero and less then some max specified in the annotation.
            // In this test, no passengers are defined so the validation fails the class-level constraint.
            val car = testclasses.Car(
                id = 1234,
                make = CarMake.Volkswagen,
                model = "Beetle",
                year = 2004,
                owners = listOf(testclasses.Person(id = "9999", name = "K. Ann", address = DefaultAddress)),
                licensePlate = "CA123",
                numDoors = 2,
                manual = true,
                ownershipStart = LocalDate.now(),
                ownershipEnd = LocalDate.now().plusYears(10),
                warrantyStart = null,
                warrantyEnd = LocalDate.now().plusYears(3),
                passengers = emptyList()
            )

            assertViolations(
                obj = car,
                withViolations = listOf(
                    WithViolation("", "number of passenger(s) is not valid", car, testclasses.Car::class.java, car),
                    WithViolation("validateId", "id may not be even", car, testclasses.Car::class.java, car),
                    WithViolation(
                        "warrantyTimeValid.warrantyEnd",
                        "both warrantyStart and warrantyEnd are required for a valid range",
                        car,
                        testclasses.Car::class.java,
                        car
                    ),
                    WithViolation(
                        "warrantyTimeValid.warrantyStart",
                        "both warrantyStart and warrantyEnd are required for a valid range",
                        car,
                        testclasses.Car::class.java,
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
            violation.rootBeanClass should be(testclasses.Car::class.java)
            violation.invalidValue should be(car)
            violation.rootBean should be(car)

            // Validation with the default group does not return any violations with the standard
            // Hibernate Validator because the constraint annotations of the data class are by default
            // only on the constructor unless specified with the @field:Annotation meta annotation.
            // However, class-level annotations will work with the standard Hibernate Validator.
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
            classLevelConstraintViolation.rootBeanClass should be(testclasses.Car::class.java)
            classLevelConstraintViolation.rootBean should be(car)
        }

        test("DataClassValidator#cascaded validations") {
            val validUser = testclasses.User("1234567", "ion", "Other")
            val invalidUser = testclasses.User("", "anion", "M")
            val nestedValidUser = testclasses.Users(listOf(validUser))
            val nestedInvalidUser = testclasses.Users(listOf(invalidUser))
            val nestedDuplicateUser = testclasses.Users(listOf(validUser, validUser))

            assertViolations(obj = validUser)
            assertViolations(
                obj = invalidUser,
                withViolations = listOf(
                    WithViolation("id", "must not be empty", "", testclasses.User::class.java, invalidUser)
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
                        testclasses.Users::class.java,
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
            val testUser = testclasses.User(id = "9999", name = "April", gender = "F")
            validator.validate(testUser).isEmpty() should be(true)
        }

        test("DataClassValidator#validate returns valid result even when data class has other fields") {
            val testPerson = testclasses.Person(id = "9999", name = "April", address = DefaultAddress)
            validator.validate(testPerson).isEmpty() should be(true)
        }

        test("DataClassValidator#validate is invalid") {
            val testUser = testclasses.User(id = "", name = "April", gender = "F")
            val fieldAnnotation = getValidationAnnotations(testclasses.User::class, "id").first()

            assertViolations(
                obj = testUser,
                withViolations = listOf(
                    WithViolation(
                        "id",
                        "must not be empty",
                        "",
                        testclasses.User::class.java,
                        testUser,
                        fieldAnnotation
                    )
                )
            )
        }

        test(
            "DataClassValidator#validate returns invalid result of both field validations and method validations"
        ) {
            val testUser = testclasses.User(id = "", name = "", gender = "Female")

            val idAnnotation: Annotation = getValidationAnnotations(testclasses.User::class, "id").first()
            val genderAnnotation: Annotation = getValidationAnnotations(testclasses.User::class, "gender").first()
            val methodAnnotation: Annotation = getValidationAnnotations(testclasses.User::class, "nameCheck").first()

            assertViolations(
                obj = testUser,
                withViolations = listOf(
                    WithViolation(
                        "gender",
                        "Female not one of [F, M, Other]",
                        "Female",
                        testclasses.User::class.java,
                        testUser,
                        genderAnnotation
                    ),
                    WithViolation("id", "must not be empty", "", testclasses.User::class.java, testUser, idAnnotation),
                    WithViolation(
                        "nameCheck.name",
                        "cannot be empty",
                        testUser,
                        testclasses.User::class.java,
                        testUser,
                        methodAnnotation
                    )
                )
            )
        }

        test("DataClassValidator#should register the user defined constraint validator") {
            val testState = testclasses.StateValidationExample(state = "NY")
            val fieldAnnotation: Annotation =
                getValidationAnnotations(testclasses.StateValidationExample::class, "state").first()

            assertViolations(
                obj = testState,
                withViolations = listOf(
                    WithViolation(
                        "state",
                        "Please register with state CA",
                        "NY",
                        testclasses.StateValidationExample::class.java,
                        testState,
                        fieldAnnotation
                    )
                )
            )
        }

        test("DataClassValidator#secondary data class constructors") {
            // the framework does not validate on construction, however, it must use
            // the data class executable for finding the field validation annotations.
            // Validations only apply to executable parameters that are also class fields,
            // e.g., from the primary executable.
            assertViolations(obj = testclasses.TestJsonCreator("42"))
            assertViolations(obj = testclasses.TestJsonCreator(42))
            assertViolations(obj = testclasses.TestJsonCreator2(listOf("1", "2", "3")))
            assertViolations(
                obj = testclasses.TestJsonCreator2(listOf(1, 2, 3), default = "Goodbye, world")
            )

            assertViolations(obj = testclasses.TestJsonCreatorWithValidation("42"))
            assertThrows<NumberFormatException> {
                // can't validate after the fact -- the instance is already constructed, then we validate
                assertViolations(obj = testclasses.TestJsonCreatorWithValidation(""))
            }
            assertViolations(obj = testclasses.TestJsonCreatorWithValidation(42))

            // annotations are on primary executable
            assertViolations(
                obj = testclasses.TestJsonCreatorWithValidations("99"),
                withViolations = listOf(
                    WithViolation(
                        "int",
                        "99 not one of [42, 137]",
                        99,
                        testclasses.TestJsonCreatorWithValidations::class.java,
                        testclasses.TestJsonCreatorWithValidations("99")
                    )
                )
            )
            assertViolations(obj = testclasses.TestJsonCreatorWithValidations(42))

            assertViolations(obj = testclasses.DataClassWithMultipleConstructors("10001", "20002", "30003"))
            assertViolations(obj = testclasses.DataClassWithMultipleConstructors(10001L, 20002L, 30003L))

            assertViolations(obj = testclasses.DataClassWithMultipleConstructorsAnnotated(10001L, 20002L, 30003L))
            assertViolations(
                obj =
                testclasses.DataClassWithMultipleConstructorsAnnotated(
                    "10001", "20002", "30003"
                )
            )

            assertViolations(
                obj = testclasses.DataClassWithMultipleConstructorsAnnotatedAndValidations(
                    10001L,
                    20002L,
                    UUID.randomUUID().toString()
                )
            )

            assertViolations(
                obj = testclasses.DataClassWithMultipleConstructorsAnnotatedAndValidations(
                    "10001",
                    "20002",
                    UUID.randomUUID().toString()
                )
            )

            val invalid = testclasses.DataClassWithMultipleConstructorsPrimaryAnnotatedAndValidations(
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
                        testclasses.DataClassWithMultipleConstructorsPrimaryAnnotatedAndValidations::class.java,
                        invalid
                    )
                )
            )

            val d = testclasses.InvalidDoublePerson("Andrea") { "" }
            assertViolations(d)
            // underlying also ignores function arguments
            validator.underlying.validate(d).size should be(0)
            val d2 = testclasses.DoublePerson("Andrea") { "" }
            assertViolations(d2)
            // underlying also ignores function arguments
            validator.underlying.validate(d2).size should be(0)

            // verification fails here because of the method validation
            val d3 = testclasses.ValidDoublePerson("Andrea") { "" }
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

            val d4 = testclasses.PossiblyValidDoublePerson("Andrea") { "" }
            assertViolations(d4)
            // underlying also ignores function arguments
            validator.underlying.validate(d4).size should be(0)

            val d5 = testclasses.WithFinalValField(
                UUID.randomUUID().toString(),
                "Joan",
                "Jett",
                setOf(1, 2, 3)
            )
            assertViolations(d5)
        }

        test("DataClassValidator#cycles") {
            validator.validate(testclasses.A("5678", testclasses.B("9876", testclasses.C("444", null))))
                .isEmpty() should be(true)
            validator.validate(testclasses.D("1", testclasses.E("2", testclasses.F("3", null)))).isEmpty() should be(
                true
            )
            validator.validate(testclasses.G("1", testclasses.H("2", testclasses.I("3", emptyList()))))
                .isEmpty() should be(true)
        }

        test("DataClassValidator#java BeanProperty") {
            val value = testclasses.AnnotatedBeanProperties(3)
            value.field1 = ""
            assertViolations(
                obj = value,
                withViolations = listOf(
                    WithViolation(
                        "field1",
                        "must not be empty",
                        "",
                        testclasses.AnnotatedBeanProperties::class.java,
                        value
                    )
                )
            )
        }

        test("DataClassValidator#data class with no executable params") {
            val value = testclasses.NoConstructorParams(null)
            value.id = "1234"
            assertViolations(value)

            assertViolations(
                obj = testclasses.NoConstructorParams(null),
                withViolations = listOf(
                    WithViolation(
                        "id",
                        "must not be empty",
                        "",
                        testclasses.NoConstructorParams::class.java,
                        testclasses.NoConstructorParams(null)
                    )
                )
            )
        }

        test("DataClassValidator#data class annotated non executable field") {
            assertViolations(
                obj = testclasses.AnnotatedInternalFields("1234", "thisisabigstring"),
                withViolations = listOf(
                    WithViolation(
                        "company",
                        "must not be empty",
                        "",
                        testclasses.AnnotatedInternalFields::class.java,
                        testclasses.AnnotatedInternalFields("1234", "thisisabigstring")
                    )
                )
            )
        }

        test("DataClassValidator#inherited validation annotations") {
            assertViolations(
                obj = testclasses.ImplementsAncestor(""),
                withViolations = listOf(
                    WithViolation(
                        "field1",
                        "must not be empty",
                        "",
                        testclasses.ImplementsAncestor::class.java,
                        testclasses.ImplementsAncestor("")
                    ),
                    WithViolation(
                        "validateField1.field1",
                        "not a double value",
                        testclasses.ImplementsAncestor(""),
                        testclasses.ImplementsAncestor::class.java,
                        testclasses.ImplementsAncestor("")
                    )
                )
            )

            assertViolations(
                obj = testclasses.ImplementsAncestor("blimey"),
                withViolations = listOf(
                    WithViolation(
                        "validateField1.field1",
                        "not a double value",
                        testclasses.ImplementsAncestor("blimey"),
                        testclasses.ImplementsAncestor::class.java,
                        testclasses.ImplementsAncestor("blimey")
                    )
                )
            )

            assertViolations(obj = testclasses.ImplementsAncestor("3.141592653589793d"))
        }

        test("DataClassValidator#getConstraintsForClass") {
            // there is no resolution of nested types in a description
            val driverDataClassDescriptor = validator.getConstraintsForClass(testclasses.Driver::class)
            driverDataClassDescriptor.members.size should be(2)

            verifyDriverClassDescriptor(driverDataClassDescriptor)
        }

        test("DataClassValidator#annotations") {
            val dataClassDescriptor = validator.descriptorFactory.describe(testclasses.TestAnnotations::class)
            dataClassDescriptor.members.size should be(1)
            val (name, memberDescriptor) = dataClassDescriptor.members.entries.first()
            name should be("cars")
            memberDescriptor.isInstanceOf<PropertyDescriptor>() should be(true)
            memberDescriptor.annotations.size should be(1)
            memberDescriptor.annotations.first().annotationClass should be(NotEmpty::class)
        }

        test("DataClassValidator#generics 1") {
            val dataClassDescriptor =
                validator.descriptorFactory.describe(testclasses.GenericTestDataClass::class)
            dataClassDescriptor.members.size should be(1)
            val value = testclasses.GenericTestDataClass(data = "Hello, World")
            val results = validator.validate(value)
            results.isEmpty() should be(true)

            assertThrows<UnexpectedTypeException> {
                validator.validate(testclasses.GenericTestDataClass(3))
            }

            val genericValue = testclasses.GenericTestDataClass(emptyList<String>())
            val violations = validator.validate(genericValue)
            violations.size should be(1)
            val violation = violations.first()
            violation.message should be("must not be empty")
            violation.propertyPath.toString() should be("data")
            violation.rootBeanClass should be(testclasses.GenericTestDataClass::class.java)
            violation.leafBean should be(genericValue)
            violation.invalidValue should be(emptyList<String>())

            validator.validate(testclasses.GenericMinTestDataClass(5)).isEmpty() should be(true)

            val dataClassDescriptor1 =
                validator.descriptorFactory.describe(testclasses.GenericTestDataClassMultipleTypes::class)
            dataClassDescriptor1.members.size should be(3)

            val value1 = testclasses.GenericTestDataClassMultipleTypes(
                data = null,
                things = listOf(1, 2, 3),
                otherThings = listOf(
                    testclasses.UUIDExample("1234"),
                    testclasses.UUIDExample(UUID.randomUUID().toString()),
                    testclasses.UUIDExample(UUID.randomUUID().toString())
                )
            )
            val results1 = validator.validate(value1)
            results1.isEmpty() should be(true)
        }

        test("DataClassValidator#generics 2") {
            val value: testclasses.Page<testclasses.Person> = testclasses.Page(
                listOf(
                    testclasses.Person(id = "9999", name = "April", address = DefaultAddress),
                    testclasses.Person(id = "9999", name = "April", address = DefaultAddress)
                ),
                0,
                null,
                null
            )

            val dataClassDescriptor =
                validator.descriptorFactory.describe(testclasses.Page::class)
            dataClassDescriptor.members.size should be(4)

            val violations = validator.validate(value)
            violations.size should be(1)

            val violation = violations.first()
            violation.propertyPath.toString() should be("pageSize")
            violation.message should be("must be greater than or equal to 1")
            violation.rootBeanClass should be(testclasses.Page::class.java)
            violation.rootBean should be(value)
            violation.invalidValue should be(0)
        }

        test("DataClassValidator#test boxed primitives") {
            validator
                .validateValue(
                    testclasses.DataClassWithBoxedPrimitives::class,
                    "events",
                    Integer.valueOf(42)
                ).isEmpty() should be(true)
        }

        test("DataClassValidator#find executable 1") {
            /* DataClassWithMultipleConstructorsAnnotatedAndValidations */
            val kClazz = testclasses.DataClassWithMultipleConstructorsAnnotatedAndValidations::class

            // by default this will find listOf(Long, Long, String)
            val constructorParameterTypes = kClazz.primaryConstructor!!.parameters.map { it.type.javaType }
            constructorParameterTypes.size should be(3)
            constructorParameterTypes[0] should be(Long::class.javaPrimitiveType)
            constructorParameterTypes[1] should be(Long::class.javaPrimitiveType)
            constructorParameterTypes[2] should be(String::class.java)

            val dataClazzDescriptor = validator.getConstraintsForClass(
                testclasses.DataClassWithMultipleConstructorsAnnotatedAndValidations::class
            )
            dataClazzDescriptor.members.size should be(3)
            dataClazzDescriptor.members.contains("number1") should be(true)
            dataClazzDescriptor.members["number1"]!!.annotations.isEmpty() should be(true)
            dataClazzDescriptor.members.contains("number2") should be(true)
            dataClazzDescriptor.members["number2"]!!.annotations.isEmpty() should be(true)
            dataClazzDescriptor.members.contains("uuid") should be(true)
            dataClazzDescriptor.members["uuid"]!!.annotations.isEmpty() should be(true)
        }

        test("DataClassValidator#find executable 2") {
            /* DataClassWithMultipleConstructorsPrimaryAnnotatedAndValidations */
            val kClazz = testclasses.DataClassWithMultipleConstructorsPrimaryAnnotatedAndValidations::class

            // by default this will find listOf(Long, Long, String)
            val constructorParameterTypes = kClazz.primaryConstructor!!.parameters.map { it.type.javaType }
            constructorParameterTypes.size should be(3)
            constructorParameterTypes[0] should be(Long::class.javaPrimitiveType)
            constructorParameterTypes[1] should be(Long::class.javaPrimitiveType)
            constructorParameterTypes[2] should be(String::class.java)

            val dataClazzDescriptor = validator.getConstraintsForClass(
                testclasses.DataClassWithMultipleConstructorsPrimaryAnnotatedAndValidations::class
            )
            dataClazzDescriptor.members.size should be(3)
            dataClazzDescriptor.members.contains("number1") should be(true)
            dataClazzDescriptor.members["number1"]!!.annotations.size should be(1)
            dataClazzDescriptor.members.contains("number2") should be(true)
            dataClazzDescriptor.members["number2"]!!.annotations.size should be(1)
            dataClazzDescriptor.members.contains("uuid") should be(true)
            dataClazzDescriptor.members["uuid"]!!.annotations.size should be(1)
        }

        test("DataClassValidator#find executable 3") {
            /* DataClassWithMultipleConstructorsAnnotated */
            val kClazz = testclasses.DataClassWithMultipleConstructorsAnnotated::class

            // this should find listOf(Long, Long, Long)
            val constructorParameterTypes = kClazz.primaryConstructor!!.parameters.map { it.type.javaType }
            constructorParameterTypes.size should be(3)
            constructorParameterTypes[0] should be(Long::class.javaPrimitiveType)
            constructorParameterTypes[1] should be(Long::class.javaPrimitiveType)
            constructorParameterTypes[2] should be(Long::class.javaPrimitiveType)

            val dataClazzDescriptor =
                validator.getConstraintsForClass(testclasses.DataClassWithMultipleConstructorsAnnotated::class)
            dataClazzDescriptor.members.size should be(3)
            dataClazzDescriptor.members.contains("number1") should be(true)
            dataClazzDescriptor.members["number1"]!!.annotations.isEmpty() should be(true)
            dataClazzDescriptor.members.contains("number2") should be(true)
            dataClazzDescriptor.members["number2"]!!.annotations.isEmpty() should be(true)
            dataClazzDescriptor.members.contains("number3") should be(true)
            dataClazzDescriptor.members["number3"]!!.annotations.isEmpty() should be(true)
        }

        test("DataClassValidator#find executable 4") {
            /* DataClassWithMultipleConstructors */
            val kClazz = testclasses.DataClassWithMultipleConstructors::class

            // this should find listOf(Long, Long, Long)
            val constructorParameterTypes = kClazz.primaryConstructor!!.parameters.map { it.type.javaType }
            constructorParameterTypes.size should be(3)
            constructorParameterTypes[0] should be(Long::class.javaPrimitiveType)
            constructorParameterTypes[1] should be(Long::class.javaPrimitiveType)
            constructorParameterTypes[2] should be(Long::class.javaPrimitiveType)

            val dataClazzDescriptor =
                validator.getConstraintsForClass(testclasses.DataClassWithMultipleConstructors::class)
            dataClazzDescriptor.members.size should be(3)
            dataClazzDescriptor.members.contains("number1") should be(true)
            dataClazzDescriptor.members["number1"]!!.annotations.isEmpty() should be(true)
            dataClazzDescriptor.members.contains("number2") should be(true)
            dataClazzDescriptor.members["number2"]!!.annotations.isEmpty() should be(true)
            dataClazzDescriptor.members.contains("number3") should be(true)
            dataClazzDescriptor.members["number3"]!!.annotations.isEmpty() should be(true)
        }

        test("DataClassValidator#find executable 5") {
            /* TestJsonCreatorWithValidations */
            val kClazz = testclasses.TestJsonCreatorWithValidations::class

            // should find listOf(int)
            val constructorParameterTypes = kClazz.primaryConstructor!!.parameters.map { it.type.javaType }
            constructorParameterTypes.size should be(1)
            constructorParameterTypes.first() should be(Int::class.javaPrimitiveType)

            val dataClazzDescriptor =
                validator.getConstraintsForClass(testclasses.TestJsonCreatorWithValidations::class)
            dataClazzDescriptor.members.size should be(1)
            dataClazzDescriptor.members.contains("int") should be(true)
            dataClazzDescriptor.members["int"]!!.annotations.size should be(1)
        }

        test("DataClassValidator#with Logging trait") {
            val value = testclasses.PersonWithLogging(42, "Baz Var", null)
            validator.validate(value).isEmpty() should be(true)
        }

        test("DataClassValidator#class with incorrect method validation definition 1") {
            val value = testclasses.WithIncorrectlyDefinedMethodValidation("abcd1234")

            val e = assertThrows<ConstraintDeclarationException> {
                validator.validate(value)
            }
            e.message should be(
                "Methods annotated with @MethodValidation must not declare any arguments"
            )
        }

        test("DataClassValidator#class with incorrect method validation definition 2") {
            val value = testclasses.AnotherIncorrectlyDefinedMethodValidation("abcd1234")

            val e = assertThrows<ConstraintDeclarationException> {
                validator.validate(value)
            }
            e.message should be(
                "Methods annotated with @MethodValidation must return a MethodValidationResult"
            )
        }
    }

    @Suppress("UNCHECKED_CAST", "PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private fun newJavaSet(numElements: Int): java.util.Set<Int> {
        val result = HashSet<Int>()
        for (i in 1..numElements) {
            result.add(i)
        }
        return result as java.util.Set<Int>
    }

    private fun verifyDriverClassDescriptor(
        driverDataClassDescriptor: DataClassDescriptor<testclasses.Driver>
    ) {
        val person = driverDataClassDescriptor.members["person"]!!
        person.kotlinType should be(testclasses.Person::class.createType())
        // cascaded types will have non-null 'cascadedType'
        person.cascadedType shouldNot beNull()
        person.cascadedType should be(testclasses.Person::class.createType())
        person.annotations.isEmpty() should be(true)
        person.isCascaded should be(true)

        // person is cascaded, so let's look it up
        val personDescriptor = validator.getConstraintsForClass(testclasses.Person::class)
        personDescriptor.members.size should be(6)
        val city = personDescriptor.members["city"]!!
        city.kotlinType should be(String::class.createType())
        city.annotations.isEmpty() should be(true)
        city.isCascaded should be(false)

        val name = personDescriptor.members["name"]!!
        name.kotlinType should be(String::class.createType())
        name.annotations.size should be(1)
        name.annotations.first().annotationClass should be(NotEmpty::class)
        name.isCascaded should be(false)

        val state = personDescriptor.members["state"]!!
        state.kotlinType should be(String::class.createType())
        state.annotations.isEmpty() should be(true)
        state.isCascaded should be(false)

        val company = personDescriptor.members["company"]!!
        company.kotlinType should be(String::class.createType())
        company.annotations.isEmpty() should be(true)
        company.isCascaded should be(false)

        val id = personDescriptor.members["id"]!!
        id.kotlinType should be(String::class.createType())
        id.annotations.size should be(1)
        id.annotations.first().annotationClass should be(NotEmpty::class)
        id.isCascaded should be(false)

        val address = personDescriptor.members["address"]!!
        address.kotlinType should be(testclasses.Address::class.createType())
        address.annotations.isEmpty() should be(true)
        address.isCascaded should be(true)

        // address is cascaded, so let's look it up
        val addressDescriptor = validator.getConstraintsForClass(testclasses.Address::class)
        addressDescriptor.members.size should be(6)

        val line1 = addressDescriptor.members["line1"]!!
        line1.kotlinType should be(String::class.createType())
        line1.annotations.isEmpty() should be(true)
        line1.isCascaded should be(false)

        val line2 = addressDescriptor.members["line2"]!!
        line2.kotlinType should be(String::class.createType(nullable = true))
        line2.annotations.isEmpty() should be(true)
        line2.isCascaded should be(false)

        val addressCity = addressDescriptor.members["city"]!!
        addressCity.kotlinType should be(String::class.createType())
        addressCity.annotations.isEmpty() should be(true)
        addressCity.isCascaded should be(false)

        val addressState = addressDescriptor.members["state"]!!
        addressState.kotlinType should be(String::class.createType())
        addressState.annotations.size should be(1)
        addressState.annotations.first().annotationClass should be(StateConstraint::class)
        addressState.isCascaded should be(false)

        val zipcode = addressDescriptor.members["zipcode"]!!
        zipcode.kotlinType should be(String::class.createType())
        zipcode.annotations.isEmpty() should be(true)
        zipcode.isCascaded should be(false)

        val additionalPostalCode = addressDescriptor.members["additionalPostalCode"]!!
        additionalPostalCode.kotlinType should be(String::class.createType(nullable = true))
        // not cascaded so no contained type
        additionalPostalCode.cascadedType should beNull()
        additionalPostalCode.annotations.isEmpty() should be(true)
        additionalPostalCode.isCascaded should be(false)

        personDescriptor.methods.size should be(1)
        personDescriptor.methods.first().callable.name should be("validateAddress")
        personDescriptor.methods.first().annotations.size should be(1)
        personDescriptor.methods.first().annotations.first().annotationClass should be(
            MethodValidation::class
        )
        personDescriptor.methods.first().members.size should be(0)

        val car = driverDataClassDescriptor.members["car"]!!
        car.kotlinType should be(testclasses.Car::class.createType())
        car.cascadedType shouldNot beNull()
        car.cascadedType should be(testclasses.Car::class.createType())
        car.annotations.isEmpty() should be(true)
        car.isCascaded should be(true)

        // car is cascaded, so let's look it up
        val carDescriptor = validator.getConstraintsForClass(testclasses.Car::class)
        carDescriptor.members.size should be(13)

        // just look at annotated and cascaded fields in car
        val year = carDescriptor.members["year"]!!
        year.kotlinType should be(Int::class.createType())
        year.annotations.size should be(1)
        year.annotations.first().annotationClass should be(Min::class)
        year.isCascaded should be(false)

        val owners = carDescriptor.members["owners"]!!
        owners.kotlinType should be(
            List::class.createType(
                arguments =
                listOf(
                    KTypeProjection(
                        KVariance.INVARIANT,
                        testclasses.Person::class.createType()
                    )
                )
            )
        )
        owners.cascadedType shouldNot beNull()
        owners.cascadedType should be(testclasses.Person::class.createType())
        owners.annotations.isEmpty() should be(true)
        owners.isCascaded should be(true)

        val licensePlate = carDescriptor.members["licensePlate"]!!
        licensePlate.kotlinType should be(String::class.createType())
        licensePlate.annotations.size should be(2)
        licensePlate.annotations.first().annotationClass should be(NotEmpty::class)
        licensePlate.annotations.last().annotationClass should be(Size::class)
        licensePlate.isCascaded should be(false)

        val numDoors = carDescriptor.members["numDoors"]!!
        numDoors.kotlinType should be(Int::class.createType())
        numDoors.annotations.size should be(1)
        numDoors.annotations.first().annotationClass should be(Min::class)
        numDoors.isCascaded should be(false)

        val passengers = carDescriptor.members["passengers"]!!
        passengers.kotlinType should be(
            List::class.createType(
                listOf(
                    KTypeProjection(
                        KVariance.INVARIANT,
                        testclasses.Person::class.createType()
                    )
                )
            )
        )
        passengers.cascadedType shouldNot beNull()
        passengers.cascadedType should be(testclasses.Person::class.createType())
        passengers.annotations.isEmpty() should be(true)
        passengers.isCascaded should be(true)

        carDescriptor.methods.size should be(4)
        val mappedCarMethods = carDescriptor.methods.associateBy { methodDescriptor -> methodDescriptor.callable.name }

        val validateId = mappedCarMethods["validateId"]
        validateId shouldNot beNull()
        validateId!!.members.isEmpty() should be(true)
        validateId.annotations.size should be(1)
        validateId.annotations.first().annotationClass should be(MethodValidation::class)
        validateId.members.size should be(0)

        val validateYearBeforeNow = mappedCarMethods["validateYearBeforeNow"]
        validateYearBeforeNow shouldNot beNull()
        validateYearBeforeNow!!.members.isEmpty() should be(true)
        validateYearBeforeNow.annotations.size should be(1)
        validateYearBeforeNow.annotations.first().annotationClass should be(MethodValidation::class)
        validateYearBeforeNow.members.size should be(0)

        val ownershipTimesValid = mappedCarMethods["ownershipTimesValid"]
        ownershipTimesValid shouldNot beNull()
        ownershipTimesValid!!.members.isEmpty() should be(true)
        ownershipTimesValid.annotations.size should be(1)
        ownershipTimesValid.annotations.first().annotationClass should be(MethodValidation::class)
        ownershipTimesValid.members.size should be(0)

        val warrantyTimeValid = mappedCarMethods["warrantyTimeValid"]
        warrantyTimeValid shouldNot beNull()
        warrantyTimeValid!!.members.isEmpty() should be(true)
        warrantyTimeValid.annotations.size should be(1)
        warrantyTimeValid.annotations.first().annotationClass should be(MethodValidation::class)
        warrantyTimeValid.members.size should be(0)
    }

    private fun getValidationAnnotations(clazz: KClass<*>, name: String): List<Annotation> {
        val descriptor = validator.descriptorFactory.describe(clazz)
        return when (val property = descriptor.members[name]) {
            null -> {
                when (val method = descriptor.methods.find { it.callable.name == name }) {
                    null -> emptyList()
                    else -> method.annotations
                }
            }

            else -> property.annotations
        }
    }
}
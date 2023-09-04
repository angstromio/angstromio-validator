package angstromio.validation.executable

import angstromio.validation.AssertViolationTest
import angstromio.validation.CarMake
import angstromio.validation.DataClassValidator
import angstromio.validation.DataClassValidatorTest.Companion.DefaultAddress
import angstromio.validation.cfg.ConstraintMapping
import angstromio.validation.constraints.ValidPassengerCountReturnValue
import angstromio.validation.constraints.ValidPassengerCountReturnValueConstraintValidator
import angstromio.validation.extensions.sorted
import angstromio.validation.testclasses
import io.kotest.matchers.be
import io.kotest.matchers.equals.shouldBeEqual
import io.kotest.matchers.nulls.beNull
import io.kotest.matchers.should
import jakarta.validation.ConstraintViolation
import jakarta.validation.UnexpectedTypeException
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.jvm.jvmName
import kotlin.reflect.jvm.kotlinFunction

class DataClassExecutableValidatorTest : AssertViolationTest() {

    override val validator: DataClassValidator = DataClassValidator.builder()
        .withConstraintMappings(
            setOf(
                ConstraintMapping(
                    ValidPassengerCountReturnValue::class,
                    ValidPassengerCountReturnValueConstraintValidator::class
                )
            )
        )
        .validator()

    private val executableValidator: DataClassExecutableValidator = validator.forExecutables()

    init {

        test("DataClassExecutableValidator#validateMethods 1") {
            val owner = testclasses.Person(id = "", name = "A. Einstein", address = DefaultAddress)
            val car = testclasses.Car(
                id = 1234,
                make = CarMake.Volkswagen,
                model = "Beetle",
                year = 1970,
                owners = listOf(owner),
                licensePlate = "CA123",
                numDoors = 2,
                manual = true,
                ownershipStart = LocalDate.now(),
                ownershipEnd = LocalDate.now().plusYears(10),
                warrantyStart = null,
                warrantyEnd = LocalDate.now().plusYears(3),
                passengers = listOf(
                    testclasses.Person(id = "1001", name = "R. Franklin", address = DefaultAddress)
                )
            )

            val violations = executableValidator.validateMethods(car)
            assertViolations(
                violations,
                listOf(
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
        }

        test("DataClassExecutableValidator#validateMethods 3") {
            val owner = testclasses.Person(id = "", name = "A. Einstein", address = DefaultAddress)
            val car = testclasses.Car(
                id = 1234,
                make = CarMake.Volkswagen,
                model = "Beetle",
                year = 1970,
                owners = listOf(owner),
                licensePlate = "CA123",
                numDoors = 2,
                manual = true,
                ownershipStart = LocalDate.now(),
                ownershipEnd = LocalDate.now().plusYears(10),
                warrantyStart = null,
                warrantyEnd = LocalDate.now().plusYears(3),
                passengers = listOf(
                    testclasses.Person(id = "1001", name = "R. Franklin", address = DefaultAddress)
                )
            )

            val methods = validator.describeMethods(testclasses.Car::class)
            val violations = executableValidator.validateMethods(methods, car)
            assertViolations(
                violations,
                listOf(
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
        }

        test("DataClassExecutableValidator#validateMethods 4") {
            val nestedUser = testclasses.NestedUser(
                "abcd1234",
                testclasses.Person(id = "1234abcd", name = "A. Einstein", address = DefaultAddress),
                "Other",
                ""
            )
            val methods = validator.describeMethods(testclasses.NestedUser::class)
            val violations = executableValidator.validateMethods(methods, nestedUser)
            assertViolations(
                violations,
                listOf(
                    WithViolation(
                        "jobCheck.job",
                        "cannot be empty",
                        nestedUser,
                        testclasses.NestedUser::class.java,
                        nestedUser
                    )
                )
            )
        }

        test("DataClassExecutableValidator#validateMethod") {
            val owner = testclasses.Person(id = "", name = "A. Einstein", address = DefaultAddress)
            val car = testclasses.Car(
                id = 1234,
                make = CarMake.Volkswagen,
                model = "Beetle",
                year = 1970,
                owners = listOf(owner),
                licensePlate = "CA123",
                numDoors = 2,
                manual = true,
                ownershipStart = LocalDate.now(),
                ownershipEnd = LocalDate.now().plusYears(10),
                warrantyStart = null,
                warrantyEnd = LocalDate.now().plusYears(3),
                passengers = listOf(
                    testclasses.Person(id = "1001", name = "R. Franklin", address = DefaultAddress)
                )
            )

            val method = testclasses.Car::class.declaredMemberFunctions.find { it.name == "validateId" }!!
            val violations = executableValidator.validateMethod(car, method)
            assertViolations(
                violations,
                listOf(
                    WithViolation("validateId", "id may not be even", car, testclasses.Car::class.java, car)
                )
            )
        }

        test("DataClassExecutableValidator#validateParameters 1") {
            val customer = testclasses.Customer("Jane", "Smith")
            val rentalStation = testclasses.RentalStation("Hertz")

            val method = getMethod<Unit>(
                testclasses.RentalStation::class.java,
                "rentCar",
                testclasses.Customer::class.java,
                LocalDate::class.java,
                Int::class.java
            )
            val violations: Set<ConstraintViolation<testclasses.RentalStation>> =
                executableValidator.validateParameters(
                    rentalStation,
                    method,
                    listOf(customer, LocalDate.now().minusDays(1), 5)
                )
            violations.size shouldBeEqual 1
            val invalidRentalStartDateViolation = violations.first()
            invalidRentalStartDateViolation.message should be("must be a future date")
            invalidRentalStartDateViolation.propertyPath.toString() should be("rentCar.start")
            invalidRentalStartDateViolation.rootBeanClass should be(testclasses.RentalStation::class.java)
            invalidRentalStartDateViolation.rootBean should be(rentalStation)
            invalidRentalStartDateViolation.leafBean should be(rentalStation)

            val invalidRentalStartDateAndNullCustomerViolations: Set<ConstraintViolation<testclasses.RentalStation>> =
                executableValidator.validateParameters(
                    rentalStation,
                    method,
                    listOf(null, LocalDate.now(), Integer.valueOf(5))
                )
            invalidRentalStartDateAndNullCustomerViolations.size should be(2)
            val sortedInvalidRentalStartDateAndNullCustomerViolationsViolations =
                invalidRentalStartDateAndNullCustomerViolations.sorted()
            val nullCustomerViolation = sortedInvalidRentalStartDateAndNullCustomerViolationsViolations.first()
            nullCustomerViolation.message should be("must not be null")
            nullCustomerViolation.propertyPath.toString() should be("rentCar.customer")
            nullCustomerViolation.rootBeanClass should be(testclasses.RentalStation::class.java)
            nullCustomerViolation.rootBean should be(rentalStation)
            nullCustomerViolation.leafBean should be(rentalStation)

            val invalidRentalStartDateViolation2 =
                sortedInvalidRentalStartDateAndNullCustomerViolationsViolations.last()
            invalidRentalStartDateViolation2.message should be("must be a future date")
            invalidRentalStartDateViolation2.propertyPath.toString() should be("rentCar.start")
            invalidRentalStartDateViolation2.rootBeanClass should be(testclasses.RentalStation::class.java)
            invalidRentalStartDateViolation2.rootBean should be(rentalStation)
            invalidRentalStartDateViolation2.leafBean should be(rentalStation)
        }

        test("DataClassExecutableValidator#validateParameters 2") {
            val rentalStation = testclasses.RentalStation("Hertz")
            val method = getMethod<Unit>(
                testclasses.RentalStation::class.java,
                "updateCustomerRecords",
                List::class.java
            )
            val violations: Set<ConstraintViolation<testclasses.RentalStation>> =
                executableValidator.validateParameters(
                    rentalStation,
                    method,
                    listOf(emptyList<testclasses.Customer>())
                )
            violations.size should be(2)
            val sortedViolations: List<ConstraintViolation<testclasses.RentalStation>> = violations.sorted()
            val violation1 = sortedViolations.first()
            violation1.message should be("must not be empty")
            violation1.propertyPath.toString() should be("updateCustomerRecords.customers")
            violation1.rootBeanClass should be(testclasses.RentalStation::class.java)
            violation1.rootBean should be(rentalStation)
            violation1.invalidValue should be(emptyList<testclasses.Customer>())

            val violation2 = sortedViolations.last()
            violation2.message should be("size must be between 1 and 2147483647")
            violation2.propertyPath.toString() should be("updateCustomerRecords.customers")
            violation2.rootBeanClass should be(testclasses.RentalStation::class.java)
            violation2.rootBean should be(rentalStation)
            violation2.invalidValue should be(emptyList<testclasses.Customer>())
        }

        test("DataClassExecutableValidator#validateParameters 3") {
            // validation should cascade to customer parameter which is missing first name
            val rentalStation = testclasses.RentalStation("Hertz")
            val method = getMethod<Unit>(
                testclasses.RentalStation::class.java,
                "updateCustomerRecords",
                List::class.java
            )
            val violations: Set<ConstraintViolation<testclasses.RentalStation>> =
                executableValidator.validateParameters(
                    rentalStation,
                    method,
                    listOf(
                        listOf(
                            testclasses.Customer("", "Ride")
                        )
                    )
                )
            violations.size shouldBeEqual 1
            val sortedViolations: List<ConstraintViolation<testclasses.RentalStation>> =
                violations.sorted()
            val violation1 = sortedViolations.first()
            violation1.message should be("must not be empty")
            violation1.propertyPath.toString() should be("updateCustomerRecords.customers[0].first")
            violation1.rootBeanClass should be(testclasses.RentalStation::class.java)
            violation1.rootBean should be(rentalStation)
            violation1.invalidValue should be("")
        }

        test("DataClassExecutableValidator#validateParameters 4") {
            // validation should handle encoded parameter name
            val rentalStation = testclasses.RentalStation("Hertz")
            val method = getMethod<List<String>>(testclasses.RentalStation::class.java, "listCars", String::class.java)
            val violations: Set<ConstraintViolation<testclasses.RentalStation>> =
                executableValidator.validateParameters(
                    rentalStation,
                    method,
                    listOf("")
                )
            violations.size shouldBeEqual 1
            val violation = violations.first()
            violation.message should be("must not be empty")
            violation.propertyPath.toString() should be("listCars.airport-code")
            violation.rootBeanClass should be(testclasses.RentalStation::class.java)
            violation.rootBean should be(rentalStation)
            violation.invalidValue should be("")
        }

        test("DataClassExecutableValidator#validateReturnValue") {
            val rentalStation = testclasses.RentalStation("Hertz")

            val method = getMethod<List<testclasses.Customer>>(testclasses.RentalStation::class.java, "getCustomers")
            val returnValue = emptyList<testclasses.Customer>()

            val violations: Set<ConstraintViolation<testclasses.RentalStation>> =
                executableValidator.validateReturnValue(
                    rentalStation,
                    method,
                    returnValue
                )
            violations.size should be(2)
            val sortedViolations: List<ConstraintViolation<testclasses.RentalStation>> =
                violations.sorted()

            val violation1 = sortedViolations.first()
            violation1.message should be("must not be empty")
            violation1.propertyPath.toString() should be("getCustomers.<return value>")
            violation1.rootBeanClass should be(testclasses.RentalStation::class.java)
            violation1.rootBean should be(rentalStation)
            violation1.invalidValue should be(emptyList<testclasses.Customer>())

            val violation2 = sortedViolations.last()
            violation2.message should be("size must be between 1 and 2147483647")
            violation2.propertyPath.toString() should be("getCustomers.<return value>")
            violation2.rootBeanClass should be(testclasses.RentalStation::class.java)
            violation2.rootBean should be(rentalStation)
            violation2.invalidValue should be(emptyList<testclasses.Customer>())
        }

        test("DataClassExecutableValidator#validateConstructorParameters 1") {
            val constructor = getConstructor(testclasses.RentalStation::class.java, String::class.java)

            val violations: Set<ConstraintViolation<testclasses.RentalStation>> =
                executableValidator.validateConstructorParameters(
                    constructor,
                    listOf(null)
                )
            violations.size shouldBeEqual 1

            val violation = violations.first()
            violation.message should be("must not be null")
            violation.propertyPath.toString() should be("RentalStation.name")
            violation.rootBeanClass should be(testclasses.RentalStation::class.java)
            violation.rootBean should be(null)
            violation.invalidValue should be(null)
        }

        test("DataClassExecutableValidator#validateConstructorParameters 2") {
            val constructor =
                getConstructor(testclasses.UnicodeNameDataClass::class.java, Int::class.java, String::class.java)

            val violations: Set<ConstraintViolation<testclasses.UnicodeNameDataClass>> =
                executableValidator.validateConstructorParameters(
                    constructor,
                    listOf(5, "")
                )
            violations.size should be(2)

            val sortedViolations: List<ConstraintViolation<testclasses.UnicodeNameDataClass>> =
                violations.sorted()

            val violation1 = sortedViolations.first()
            violation1.message should be("must not be empty")
            violation1.propertyPath.toString() should be("UnicodeNameDataClass.name")
            violation1.rootBeanClass should be(testclasses.UnicodeNameDataClass::class.java)
            violation1.rootBean should be(null)
            violation1.invalidValue should be("")

            val violation2 = sortedViolations[1]
            violation2.message should be("must be less than or equal to 3")
            violation2.propertyPath.toString() should be("UnicodeNameDataClass.winning-id")
            violation2.rootBeanClass should be(testclasses.UnicodeNameDataClass::class.java)
            violation2.rootBean should be(null)
            violation2.invalidValue should be(5)
        }

        test("DataClassExecutableValidator#validateConstructorParameters 3") {
            val constructor = getConstructor(
                testclasses.Page::class.java,
                List::class.java,
                Int::class.java,
                Long::class.javaObjectType, // nullable means object
                Long::class.javaObjectType  // nullable means object
            )
            val violations: Set<ConstraintViolation<testclasses.Page<*>>> =
                executableValidator.validateConstructorParameters(
                    constructor,
                    listOf(
                        listOf("foo", "bar"),
                        0 as Any,
                        1,
                        2
                    )
                )
            violations.size shouldBeEqual 1
            val violation = violations.first()
            violation.propertyPath.toString() should be("Page.pageSize")
            violation.message should be("must be greater than or equal to 1")
            violation.rootBeanClass should be(testclasses.Page::class.java)
            violation.rootBean should be(null)
            violation.invalidValue should be(0)
        }

        test("DataClassExecutableValidator#validateMethodParameters 1") {
            // validation should handle encoded parameter name
            val method = getMethod<List<String>>(testclasses.RentalStation::class.java, "listCars", String::class.java)
            val violations: Set<ConstraintViolation<List<String>>> =
                executableValidator.validateMethodParameters(
                    method,
                    listOf("")
                )
            violations.size shouldBeEqual 1
            val violation = violations.first()
            violation.message should be("must not be empty")
            violation.propertyPath.toString() should be("listCars.airport-code")
            violation.rootBeanClass should be(testclasses.RentalStation::class.java)
            violation.rootBean should be(null)
            violation.invalidValue should be("")
        }

        test("DataClassExecutableValidator#validateMethodParameters 2") {
            // validation should cascade to customer parameter which is missing first name
            val method = getMethod<Unit>(
                testclasses.RentalStation::class.java,
                "updateCustomerRecords",
                List::class.java
            )
            val violations: Set<ConstraintViolation<Unit>> =
                executableValidator.validateMethodParameters(
                    method,
                    listOf(
                        listOf(
                            testclasses.Customer("", "Ride")
                        )
                    )
                )
            violations.size shouldBeEqual 1
            val sortedViolations: List<ConstraintViolation<Unit>> = violations.sorted()
            val violation1 = sortedViolations.first()
            violation1.message should be("must not be empty")
            violation1.propertyPath.toString() should be("updateCustomerRecords.customers[0].first")
            violation1.rootBeanClass should be(testclasses.RentalStation::class.java)
            violation1.rootBean should be(null)
            violation1.invalidValue should be("")
        }

        test("DataClassExecutableValidator#validateMethodParameters 3") {
            val method = getMethod<Unit>(
                testclasses.RentalStation::class.java,
                "updateCustomerRecords",
                List::class.java
            )
            val violations: Set<ConstraintViolation<Unit>> =
                executableValidator.validateMethodParameters(
                    method,
                    listOf(emptyList<testclasses.Customer>())
                )
            violations.size should be(2)
            val sortedViolations: List<ConstraintViolation<Unit>> = violations.sorted()
            val violation1 = sortedViolations.first()
            violation1.message should be("must not be empty")
            violation1.propertyPath.toString() should be("updateCustomerRecords.customers")
            violation1.rootBeanClass should be(testclasses.RentalStation::class.java)
            violation1.rootBean should be(null)
            violation1.invalidValue should be(emptyList<testclasses.Customer>())

            val violation2 = sortedViolations.last()
            violation2.message should be("size must be between 1 and 2147483647")
            violation2.propertyPath.toString() should be("updateCustomerRecords.customers")
            violation2.rootBeanClass should be(testclasses.RentalStation::class.java)
            violation2.rootBean should be(null)
            violation2.invalidValue should be(emptyList<testclasses.Customer>())
        }

        test("DataClassExecutableValidator#validateMethodParameters 4") {
            val customer = testclasses.Customer("Jane", "Smith")

            val method = getMethod<Unit>(
                testclasses.RentalStation::class.java,
                "rentCar",
                testclasses.Customer::class.java,
                LocalDate::class.java,
                Int::class.java
            )
            val violations: Set<ConstraintViolation<Unit>> =
                executableValidator.validateMethodParameters(
                    method,
                    listOf(customer, LocalDate.now().minusDays(1), Integer.valueOf(5))
                )
            violations.size shouldBeEqual 1
            val invalidRentalStartDateViolation = violations.first()
            invalidRentalStartDateViolation.message should be("must be a future date")
            invalidRentalStartDateViolation.propertyPath.toString() should be("rentCar.start")
            invalidRentalStartDateViolation.rootBeanClass should be(testclasses.RentalStation::class.java)
            invalidRentalStartDateViolation.rootBean should be(null)
            invalidRentalStartDateViolation.leafBean should be(null)

            val invalidRentalStartDateAndNullCustomerViolations: Set<ConstraintViolation<Unit>> =
                executableValidator.validateMethodParameters(
                    method,
                    listOf(null, LocalDate.now(), Integer.valueOf(5))
                )
            invalidRentalStartDateAndNullCustomerViolations.size should be(2)
            val sortedInvalidRentalStartDateAndNullCustomerViolationsViolations: List<ConstraintViolation<Unit>> =
                invalidRentalStartDateAndNullCustomerViolations.sorted()

            val nullCustomerViolation = sortedInvalidRentalStartDateAndNullCustomerViolationsViolations.first()
            nullCustomerViolation.message should be("must not be null")
            nullCustomerViolation.propertyPath.toString() should be("rentCar.customer")
            nullCustomerViolation.rootBeanClass should be(testclasses.RentalStation::class.java)
            nullCustomerViolation.rootBean should be(null)
            nullCustomerViolation.leafBean should be(null)

            val invalidRentalStartDateViolation2 =
                sortedInvalidRentalStartDateAndNullCustomerViolationsViolations.last()
            invalidRentalStartDateViolation2.message should be("must be a future date")
            invalidRentalStartDateViolation2.propertyPath.toString() should be("rentCar.start")
            invalidRentalStartDateViolation2.rootBeanClass should be(testclasses.RentalStation::class.java)
            invalidRentalStartDateViolation2.rootBean should be(null)
            invalidRentalStartDateViolation2.leafBean should be(null)
        }

        test("DataClassExecutableValidator#validateExecutableParameters 1") {
            val constructor = getConstructor(testclasses.RentalStation::class.java, String::class.java)
            val descriptor = validator.describeExecutable(constructor, null)!!

            val violations: Set<ConstraintViolation<testclasses.RentalStation>> =
                executableValidator.validateExecutableParameters(
                    descriptor,
                    listOf(null),
                    constructor.parameters.map { it.name!! }
                )
            violations.size shouldBeEqual 1

            val violation = violations.first()
            violation.message should be("must not be null")
            violation.propertyPath.toString() should be("RentalStation.name")
            violation.rootBeanClass should be(testclasses.RentalStation::class.java)
            violation.rootBean should be(null)
            violation.invalidValue should be(null)
        }

        test("DataClassExecutableValidator#validateExecutableParameters 2") {
            val constructor =
                getConstructor(testclasses.UnicodeNameDataClass::class.java, Int::class.java, String::class.java)
            val descriptor = validator.describeExecutable(constructor, null)!!

            val violations: Set<ConstraintViolation<testclasses.UnicodeNameDataClass>> =
                executableValidator.validateExecutableParameters(
                    descriptor,
                    listOf(5, ""),
                    constructor.parameters.map { it.name!! }
                )
            violations.size should be(2)

            val sortedViolations: List<ConstraintViolation<testclasses.UnicodeNameDataClass>> =
                violations.sorted()

            val violation1 = sortedViolations.first()
            violation1.message should be("must not be empty")
            violation1.propertyPath.toString() should be("UnicodeNameDataClass.name")
            violation1.rootBeanClass should be(testclasses.UnicodeNameDataClass::class.java)
            violation1.rootBean should be(null)
            violation1.invalidValue should be("")

            val violation2 = sortedViolations[1]
            violation2.message should be("must be less than or equal to 3")
            violation2.propertyPath.toString() should be("UnicodeNameDataClass.winning-id")
            violation2.rootBeanClass should be(testclasses.UnicodeNameDataClass::class.java)
            violation2.rootBean should be(null)
            violation2.invalidValue should be(5)
        }

        test("DataClassExecutableValidator#validateExecutableParameters 3") {
            val constructor = getConstructor(
                testclasses.Page::class.java,
                List::class.java,
                Int::class.java,
                Long::class.javaObjectType, // nullable means object
                Long::class.javaObjectType  // nullable means object
            )
            val descriptor = validator.describeExecutable(constructor, null)!!

            val violations: Set<ConstraintViolation<testclasses.Page<*>>> =
                executableValidator.validateExecutableParameters(
                    descriptor,
                    listOf(
                        listOf("foo", "bar"),
                        0 as Any,
                        1,
                        2
                    ),
                    constructor.parameters.map { it.name!! }
                )
            violations.size shouldBeEqual 1
            val violation = violations.first()
            violation.propertyPath.toString() should be("Page.pageSize")
            violation.message should be("must be greater than or equal to 1")
            violation.rootBeanClass should be(testclasses.Page::class.java)
            violation.rootBean should be(null)
            violation.invalidValue should be(0)
        }

        test("DataClassExecutableValidator#validateExecutableParameters 4") {
            // validation should handle encoded parameter name
            val method = getMethod<List<String>>(testclasses.RentalStation::class.java, "listCars", String::class.java)
            val descriptor = validator.describeExecutable(method, null)!!

            val violations: Set<ConstraintViolation<List<String>>> =
                executableValidator.validateExecutableParameters(
                    descriptor,
                    listOf(""),
                    method.parameters.filter { it.kind == KParameter.Kind.VALUE }.map { it.name!! }
                )
            violations.size shouldBeEqual 1
            val violation = violations.first()
            violation.message should be("must not be empty")
            violation.propertyPath.toString() should be("listCars.airport-code")
            violation.rootBeanClass should be(testclasses.RentalStation::class.java)
            violation.rootBean should be(null)
            violation.invalidValue should be("")
        }

        test("DataClassExecutableValidator#validateExecutableParameters 5") {
            // validation should cascade to customer parameter which is missing first name
            val method = getMethod<Unit>(
                testclasses.RentalStation::class.java,
                "updateCustomerRecords",
                List::class.java
            )
            val descriptor = validator.describeExecutable(method, null)!!

            val violations: Set<ConstraintViolation<Unit>> =
                executableValidator.validateExecutableParameters(
                    descriptor,
                    listOf(
                        listOf(
                            testclasses.Customer("", "Ride")
                        )
                    ),
                    method.parameters.filter { it.kind == KParameter.Kind.VALUE }.map { it.name!! }
                )
            violations.size shouldBeEqual 1
            val sortedViolations: List<ConstraintViolation<Unit>> =
                violations.sorted()
            val violation1 = sortedViolations.first()
            violation1.message should be("must not be empty")
            violation1.propertyPath.toString() should be("updateCustomerRecords.customers[0].first")
            violation1.rootBeanClass should be(testclasses.RentalStation::class.java)
            violation1.rootBean should be(null)
            violation1.invalidValue should be("")
        }

        test("DataClassExecutableValidator#validateExecutableParameters 6") {
            val method = getMethod<Unit>(
                testclasses.RentalStation::class.java,
                "updateCustomerRecords",
                List::class.java
            )
            val descriptor = validator.describeExecutable(method, null)!!

            val violations: Set<ConstraintViolation<Unit>> =
                executableValidator.validateExecutableParameters(
                    descriptor,
                    listOf(emptyList<String>()),
                    method.parameters.filter { it.kind == KParameter.Kind.VALUE }.map { it.name!! }
                )
            violations.size should be(2)
            val sortedViolations: List<ConstraintViolation<Unit>> =
                violations.sorted()
            val violation1 = sortedViolations.first()
            violation1.message should be("must not be empty")
            violation1.propertyPath.toString() should be("updateCustomerRecords.customers")
            violation1.rootBeanClass should be(testclasses.RentalStation::class.java)
            violation1.rootBean should be(null)
            violation1.invalidValue should be(emptyList<String>())

            val violation2 = sortedViolations.last()
            violation2.message should be("size must be between 1 and 2147483647")
            violation2.propertyPath.toString() should be("updateCustomerRecords.customers")
            violation2.rootBeanClass should be(testclasses.RentalStation::class.java)
            violation2.rootBean should be(null)
            violation2.invalidValue should be(emptyList<String>())
        }

        test("DataClassExecutableValidator#validateExecutableParameters 7") {
            val customer = testclasses.Customer("Jane", "Smith")

            val method = getMethod<Unit>(
                testclasses.RentalStation::class.java,
                "rentCar",
                testclasses.Customer::class.java,
                LocalDate::class.java,
                Int::class.java
            )
            val descriptor = validator.describeExecutable(method, null)!!

            val violations: Set<ConstraintViolation<Unit>> =
                executableValidator.validateExecutableParameters(
                    descriptor,
                    listOf(customer, LocalDate.now().minusDays(1), Integer.valueOf(5)),
                    method.parameters.filter { it.kind == KParameter.Kind.VALUE } .map { it.name!! }
                )
            violations.size shouldBeEqual 1
            val invalidRentalStartDateViolation = violations.first()
            invalidRentalStartDateViolation.message should be("must be a future date")
            invalidRentalStartDateViolation.propertyPath.toString() should be("rentCar.start")
            invalidRentalStartDateViolation.rootBeanClass should be(testclasses.RentalStation::class.java)
            invalidRentalStartDateViolation.rootBean should be(null)
            invalidRentalStartDateViolation.leafBean should be(null)

            val invalidRentalStartDateAndNullCustomerViolations: Set<ConstraintViolation<Unit>> =
                executableValidator.validateMethodParameters(
                    method,
                    listOf(null, LocalDate.now(), Integer.valueOf(5))
                )
            invalidRentalStartDateAndNullCustomerViolations.size should be(2)
            val sortedInvalidRentalStartDateAndNullCustomerViolationsViolations: List<ConstraintViolation<Unit>> =
                invalidRentalStartDateAndNullCustomerViolations.sorted()

            val nullCustomerViolation = sortedInvalidRentalStartDateAndNullCustomerViolationsViolations.first()
            nullCustomerViolation.message should be("must not be null")
            nullCustomerViolation.propertyPath.toString() should be("rentCar.customer")
            nullCustomerViolation.rootBeanClass should be(testclasses.RentalStation::class.java)
            nullCustomerViolation.rootBean should be(null)
            nullCustomerViolation.leafBean should be(null)

            val invalidRentalStartDateViolation2 =
                sortedInvalidRentalStartDateAndNullCustomerViolationsViolations.last()
            invalidRentalStartDateViolation2.message should be("must be a future date")
            invalidRentalStartDateViolation2.propertyPath.toString() should be("rentCar.start")
            invalidRentalStartDateViolation2.rootBeanClass should be(testclasses.RentalStation::class.java)
            invalidRentalStartDateViolation2.rootBean should be(null)
            invalidRentalStartDateViolation2.leafBean should be(null)
        }

        test("DataClassExecutableValidator#validateExecutableParameters 8") {
            // test mixin class support
            val constructor = getConstructor(testclasses.RentalStation::class.java, String::class.java)
            val descriptor = validator.describeExecutable(constructor, testclasses.RentalStationMixin::class)!!

            val violations: Set<ConstraintViolation<testclasses.RentalStation>> =
                executableValidator.validateExecutableParameters(
                    descriptor,
                    listOf("Hertz"),
                    constructor.parameters.map { it.name!! }
                )
            violations.size shouldBeEqual 1

            // this violates the @Min(10) constraint from RentalStationMixin
            val violation = violations.first()
            violation.message should be("must be greater than or equal to 10")
            violation.propertyPath.toString() should be("RentalStation.name")
            violation.rootBeanClass should be(testclasses.RentalStation::class.java)
            violation.rootBean should be(null)
            violation.invalidValue should be("Hertz")
        }

        test("DataClassExecutableValidator#validateExecutableParameters 9") {
            // test mixin class support and replacement of parameter names
            val constructor = getConstructor(testclasses.RentalStation::class.java, String::class.java)
            val descriptor = validator.describeExecutable(constructor, testclasses.RentalStationMixin::class)!!

            val violations: Set<ConstraintViolation<testclasses.RentalStation>> =
                executableValidator.validateExecutableParameters(
                    descriptor,
                    listOf("Hertz"),
                    listOf("id")
                )
            violations.size shouldBeEqual 1

            // this violates the @Min(10) constraint from RentalStationMixin
            val violation = violations.first()
            violation.message should be("must be greater than or equal to 10")
            violation.propertyPath.toString() should be("RentalStation.id")
            violation.rootBeanClass should be(testclasses.RentalStation::class.java)
            violation.rootBean should be(null)
            violation.invalidValue should be("Hertz")
        }

        test("DataClassExecutableValidator#validateExecutableParameters 10") {
            // test mixin class support with mixin that doesn't define the field
            val constructor = getConstructor(testclasses.RentalStation::class.java, String::class.java)
            val descriptor =
                validator.describeExecutable(
                    constructor,
                    testclasses.RandoMixin::class
                )!! // doesn't define any fields so no additional constraints

            val violations: Set<ConstraintViolation<testclasses.RentalStation>> =
                executableValidator.validateExecutableParameters(
                    descriptor,
                    listOf("Hertz"),
                    listOf("id")
                )
            violations.isEmpty() should be(true)
        }

        test("DataClassExecutableValidator#validateExecutableParameters 11") {
            val pathValue: testclasses.TestPath = testclasses.TestPath("foo")
            val constructor = getConstructor(
                testclasses.PathNotEmpty::class.java,
                testclasses.TestPath::class.java,
                String::class.java
            )
            val descriptor = validator.describeExecutable(constructor, null)!!

            // no validator registered `@NotEmpty` for TestPath type, should be returned as a violation not an exception
            val e = assertThrows<UnexpectedTypeException> {
                executableValidator.validateExecutableParameters(
                    descriptor,
                    listOf(pathValue, "12345"),
                    constructor.parameters.map { it.name!! }
                )
            }
            e.message should be(
                "No validator could be found for constraint 'class jakarta.validation.constraints.NotEmpty' validating type '${testclasses.TestPath::class.jvmName}'. Check configuration for 'PathNotEmpty.path'"
            )
        }

        test("DataClassExecutableValidator#validateExecutableParameters 12") {
            val constructor = getConstructor(
                testclasses.UsersRequest::class.java,
                Int::class.java,
                LocalDate::class.java,
                Boolean::class.java
            )
            val descriptor = validator.describeExecutable(constructor, null)!!

            executableValidator
                .validateExecutableParameters(
                    descriptor,
                    listOf(
                        10,
                        LocalDate.parse("2013-01-01"),
                        true
                    ),
                    constructor.parameters.map { it.name!! }
                ).isEmpty() should be(true)

            val violations: Set<ConstraintViolation<testclasses.UsersRequest>> =
                executableValidator
                    .validateExecutableParameters(
                        descriptor,
                        listOf(
                            200,
                            LocalDate.parse("2013-01-01"),
                            true
                        ),
                        constructor.parameters.map { it.name!! }
                    )
            violations.size shouldBeEqual 1

            val violation = violations.first()
            violation.message should be("must be less than or equal to 100")
            violation.propertyPath.toString() should be("UsersRequest.max")
            violation.rootBeanClass should be(testclasses.UsersRequest::class.java)
            violation.rootBean should be(null)
            violation.invalidValue should be(200)
        }

        test("DataClassExecutableValidator#validateConstructorReturnValue") {
            val car = testclasses.CarWithPassengerCount(
                listOf(
                    testclasses.Person("abcd1234", "J Doe", DefaultAddress),
                    testclasses.Person("abcd1235", "K Doe", DefaultAddress),
                    testclasses.Person("abcd1236", "L Doe", DefaultAddress),
                    testclasses.Person("abcd1237", "M Doe", DefaultAddress),
                    testclasses.Person("abcd1238", "N Doe", DefaultAddress)
                )
            )
            val constructor =
                getConstructor(testclasses.CarWithPassengerCount::class.java, List::class.java)
            val violations: Set<ConstraintViolation<testclasses.CarWithPassengerCount>> =
                executableValidator.validateConstructorReturnValue(
                    constructor,
                    car
                )
            violations.size shouldBeEqual 1

            val violation = violations.first()
            violation.message should be("number of passenger(s) is not valid")
            violation.propertyPath.toString() should be("CarWithPassengerCount.<return value>")
            violation.rootBeanClass should be(testclasses.CarWithPassengerCount::class.java)
            violation.rootBean should beNull()
            violation.invalidValue should be(car)
        }

        test("DataClassExecutableValidator#cross-parameter") {
            val rentalStation = testclasses.RentalStation("Hertz")
            val method =
                getMethod<Boolean>(
                    testclasses.RentalStation::class.java,
                    "reserve",
                    LocalDate::class.java,
                    LocalDate::class.java
                )

            val start = LocalDate.now().plusDays(1)
            val end = LocalDate.now()
            val violations = executableValidator.validateParameters(
                rentalStation,
                method,
                listOf(start, end)
            )
            violations.size shouldBeEqual 1
            val violation = violations.first()
            violation.message should be("start is not before end")
            violation.propertyPath.toString() should be("reserve.<cross-parameter>")
            violation.rootBeanClass should be(testclasses.RentalStation::class.java)
            violation.rootBean should be(rentalStation)
            violation.leafBean should be(rentalStation)
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

    private fun <T : Any> getConstructor(clazz: Class<T>, vararg parameterTypes: Class<*>): KFunction<T> {
        return clazz.getDeclaredConstructor(*parameterTypes).kotlinFunction!!
    }
}
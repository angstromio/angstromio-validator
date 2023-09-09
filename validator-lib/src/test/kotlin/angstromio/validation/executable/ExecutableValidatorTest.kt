package angstromio.validation.executable

import angstromio.validation.AssertViolationTest
import angstromio.validation.CarMake
import angstromio.validation.DataClassValidator
import angstromio.validation.TestClasses
import angstromio.validation.ValidatorTest.Companion.DefaultAddress
import angstromio.validation.cfg.ConstraintMapping
import angstromio.validation.constraints.ValidPassengerCountReturnValue
import angstromio.validation.constraints.ValidPassengerCountReturnValueConstraintValidator
import angstromio.validation.extensions.sorted
import io.kotest.matchers.be
import io.kotest.matchers.equals.shouldBeEqual
import io.kotest.matchers.should
import jakarta.validation.ConstraintViolation
import jakarta.validation.executable.ExecutableValidator
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.time.LocalDate

class ExecutableValidatorTest : AssertViolationTest() {

    override val validator: DataClassValidator = DataClassValidator.builder()
        .withConstraintMappings(
            setOf(
                ConstraintMapping(
                    ValidPassengerCountReturnValue::class.java,
                    ValidPassengerCountReturnValueConstraintValidator::class.java
                )
            )
        )
        .validator()

    private val executableValidator: ExecutableValidator = validator.forExecutables()

    init {

        test("DataClassValidator#validatePostConstructValidationMethods 1") {
            val owner = TestClasses.Person(id = "", name = "A. Einstein", address = DefaultAddress)
            val ownershipStart = LocalDate.now()
            val ownershipEnd = ownershipStart.plusYears(10)
            val warrantyEnd = ownershipStart.plusYears(3)
            val car = TestClasses.Car(
                id = 1234,
                make = CarMake.Volkswagen,
                model = "Beetle",
                year = 1970,
                owners = listOf(owner),
                licensePlate = "CA123",
                numDoors = 2,
                manual = true,
                ownershipStart = ownershipStart,
                ownershipEnd = ownershipEnd,
                warrantyStart = null,
                warrantyEnd = warrantyEnd,
                passengers = listOf(
                    TestClasses.Person(id = "1001", name = "R. Franklin", address = DefaultAddress)
                )
            )

            val violations = validator.validatePostConstructValidationMethods(car)
            assertViolations(
                violations,
                listOf(
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
        }

        test("DataClasValidator#validatePostConstructValidationMethods 2") {
            val nestedUser = TestClasses.NestedUser(
                "abcd1234",
                TestClasses.Person(id = "1234abcd", name = "A. Einstein", address = DefaultAddress),
                "Other",
                ""
            )
            val violations = validator.validatePostConstructValidationMethods(nestedUser)
            assertViolations(
                violations,
                listOf(
                    WithViolation(
                        "jobCheck.job",
                        "cannot be empty",
                        "",
                        TestClasses.NestedUser::class.java,
                        nestedUser
                    )
                )
            )
        }

        test("DataClassValidator#validatePostConstructValidationMethod") {
            val owner = TestClasses.Person(id = "", name = "A. Einstein", address = DefaultAddress)
            val ownershipStart = LocalDate.now()
            val ownershipEnd = ownershipStart.plusYears(10)
            val warrantyEnd = ownershipStart.plusYears(3)
            val car = TestClasses.Car(
                id = 1234,
                make = CarMake.Volkswagen,
                model = "Beetle",
                year = 1970,
                owners = listOf(owner),
                licensePlate = "CA123",
                numDoors = 2,
                manual = true,
                ownershipStart = ownershipStart,
                ownershipEnd = ownershipEnd,
                warrantyStart = null,
                warrantyEnd = warrantyEnd,
                passengers = listOf(
                    TestClasses.Person(id = "1001", name = "R. Franklin", address = DefaultAddress)
                )
            )

            val method = getMethod(TestClasses.Car::class.java, "validateId")
            val violations = validator.validatePostConstructValidationMethod(car, method)
            assertViolations(
                violations,
                listOf(
                    WithViolation("validateId", "id may not be even", car, TestClasses.Car::class.java, car)
                )
            )
        }

        test("ExecutableValidator#validateParameters 1") {
            val customer = TestClasses.Customer("Jane", "Smith")
            val rentalStation = TestClasses.RentalStation("Dollar")

            val method = getMethod(
                TestClasses.RentalStation::class.java,
                "rentCar",
                TestClasses.Customer::class.java,
                LocalDate::class.java,
                Int::class.java
            )
            val violations: Set<ConstraintViolation<TestClasses.RentalStation>> =
                executableValidator.validateParameters(
                    rentalStation,
                    method,
                    arrayOf(customer, LocalDate.now().minusDays(1), 5)
                )
            violations.size shouldBeEqual 1
            val invalidRentalStartDateViolation = violations.first()
            invalidRentalStartDateViolation.message should be("must be a future date")
            invalidRentalStartDateViolation.propertyPath.toString() should be("rentCar.start")
            invalidRentalStartDateViolation.rootBeanClass should be(TestClasses.RentalStation::class.java)
            invalidRentalStartDateViolation.rootBean should be(rentalStation)
            invalidRentalStartDateViolation.leafBean should be(rentalStation)

            val invalidRentalStartDateAndNullCustomerViolations: Set<ConstraintViolation<TestClasses.RentalStation>> =
                executableValidator.validateParameters(
                    rentalStation,
                    method,
                    arrayOf(null, LocalDate.now(), Integer.valueOf(5))
                )
            invalidRentalStartDateAndNullCustomerViolations.size should be(2)
            val sortedInvalidRentalStartDateAndNullCustomerViolationsViolations =
                invalidRentalStartDateAndNullCustomerViolations.sorted()
            val nullCustomerViolation = sortedInvalidRentalStartDateAndNullCustomerViolationsViolations.first()
            nullCustomerViolation.message should be("must not be null")
            nullCustomerViolation.propertyPath.toString() should be("rentCar.customer")
            nullCustomerViolation.rootBeanClass should be(TestClasses.RentalStation::class.java)
            nullCustomerViolation.rootBean should be(rentalStation)
            nullCustomerViolation.leafBean should be(rentalStation)

            val invalidRentalStartDateViolation2 =
                sortedInvalidRentalStartDateAndNullCustomerViolationsViolations.last()
            invalidRentalStartDateViolation2.message should be("must be a future date")
            invalidRentalStartDateViolation2.propertyPath.toString() should be("rentCar.start")
            invalidRentalStartDateViolation2.rootBeanClass should be(TestClasses.RentalStation::class.java)
            invalidRentalStartDateViolation2.rootBean should be(rentalStation)
            invalidRentalStartDateViolation2.leafBean should be(rentalStation)
        }

        test("ExecutableValidator#validateParameters 2") {
            val rentalStation = TestClasses.RentalStation("Budget")
            val method = getMethod(
                TestClasses.RentalStation::class.java,
                "updateCustomerRecords",
                List::class.java
            )
            val violations: Set<ConstraintViolation<TestClasses.RentalStation>> =
                executableValidator.validateParameters(
                    rentalStation,
                    method,
                    arrayOf(emptyList<TestClasses.Customer>())
                )
            violations.size should be(2)
            val sortedViolations: List<ConstraintViolation<TestClasses.RentalStation>> = violations.sorted()
            val violation1 = sortedViolations.first()
            violation1.message should be("must not be empty")
            violation1.propertyPath.toString() should be("updateCustomerRecords.customers")
            violation1.rootBeanClass should be(TestClasses.RentalStation::class.java)
            violation1.rootBean should be(rentalStation)
            violation1.invalidValue should be(emptyList<TestClasses.Customer>())

            val violation2 = sortedViolations.last()
            violation2.message should be("size must be between 1 and 2147483647")
            violation2.propertyPath.toString() should be("updateCustomerRecords.customers")
            violation2.rootBeanClass should be(TestClasses.RentalStation::class.java)
            violation2.rootBean should be(rentalStation)
            violation2.invalidValue should be(emptyList<TestClasses.Customer>())
        }

        test("ExecutableValidator#validateParameters 3") {
            // validation should cascade to customer parameter which is missing first name
            val rentalStation = TestClasses.RentalStation("Hertz")
            val method = getMethod(
                TestClasses.RentalStation::class.java,
                "updateCustomerRecords",
                List::class.java
            )
            val violations: Set<ConstraintViolation<TestClasses.RentalStation>> =
                executableValidator.validateParameters(
                    rentalStation,
                    method,
                    arrayOf(
                        listOf(
                            TestClasses.Customer("", "Ride")
                        )
                    )
                )
            violations.size shouldBeEqual 1
            val sortedViolations: List<ConstraintViolation<TestClasses.RentalStation>> =
                violations.sorted()
            val violation1 = sortedViolations.first()
            violation1.message should be("must not be empty")
            violation1.propertyPath.toString() should be("updateCustomerRecords.customers[0].first")
            violation1.rootBeanClass should be(TestClasses.RentalStation::class.java)
            violation1.rootBean should be(rentalStation)
            violation1.invalidValue should be("")
        }

        test("ExecutableValidator#validateParameters 4") {
            // validation should handle encoded parameter name
            val rentalStation = TestClasses.RentalStation("Avis")
            val method = getMethod(TestClasses.RentalStation::class.java, "listCars", String::class.java)
            val violations: Set<ConstraintViolation<TestClasses.RentalStation>> =
                executableValidator.validateParameters(
                    rentalStation,
                    method,
                    arrayOf("")
                )
            violations.size shouldBeEqual 1
            val violation = violations.first()
            violation.message should be("must not be empty")
            violation.propertyPath.toString() should be("listCars.airport-code")
            violation.rootBeanClass should be(TestClasses.RentalStation::class.java)
            violation.rootBean should be(rentalStation)
            violation.invalidValue should be("")
        }

        test("ExecutableValidator#validateReturnValue") {
            val rentalStation = TestClasses.RentalStation("Hertz")

            val method = getMethod(TestClasses.RentalStation::class.java, "getCustomers")
            val returnValue = emptyList<TestClasses.Customer>()

            val violations: Set<ConstraintViolation<TestClasses.RentalStation>> =
                executableValidator.validateReturnValue(
                    rentalStation,
                    method,
                    returnValue
                )
            violations.size should be(2)
            val sortedViolations: List<ConstraintViolation<TestClasses.RentalStation>> =
                violations.sorted()

            val violation1 = sortedViolations.first()
            violation1.message should be("must not be empty")
            violation1.propertyPath.toString() should be("getCustomers.<return value>")
            violation1.rootBeanClass should be(TestClasses.RentalStation::class.java)
            violation1.rootBean should be(rentalStation)
            violation1.invalidValue should be(emptyList<TestClasses.Customer>())

            val violation2 = sortedViolations.last()
            violation2.message should be("size must be between 1 and 2147483647")
            violation2.propertyPath.toString() should be("getCustomers.<return value>")
            violation2.rootBeanClass should be(TestClasses.RentalStation::class.java)
            violation2.rootBean should be(rentalStation)
            violation2.invalidValue should be(emptyList<TestClasses.Customer>())
        }

        test("ExecutableValidator#validateConstructorParameters 1") {
            val constructor = getConstructor(TestClasses.RentalStation::class.java, String::class.java)

            val violations: Set<ConstraintViolation<TestClasses.RentalStation>> =
                executableValidator.validateConstructorParameters(
                    constructor,
                    arrayOf(null)
                )
            violations.size shouldBeEqual 1

            val violation = violations.first()
            violation.message should be("must not be null")
            violation.propertyPath.toString() should be("RentalStation.name")
            violation.rootBeanClass should be(TestClasses.RentalStation::class.java)
            violation.rootBean should be(null)
            violation.invalidValue should be(null)
        }

        test("ExecutableValidator#validateConstructorParameters 2") {
            val constructor =
                getConstructor(TestClasses.UnicodeNameDataClass::class.java, Int::class.java, String::class.java)

            val violations: Set<ConstraintViolation<TestClasses.UnicodeNameDataClass>> =
                executableValidator.validateConstructorParameters(
                    constructor,
                    arrayOf(5, "")
                )
            violations.size should be(2)

            val sortedViolations: List<ConstraintViolation<TestClasses.UnicodeNameDataClass>> =
                violations.sorted()

            val violation1 = sortedViolations.first()
            violation1.message should be("must not be empty")
            violation1.propertyPath.toString() should be("UnicodeNameDataClass.name")
            violation1.rootBeanClass should be(TestClasses.UnicodeNameDataClass::class.java)
            violation1.rootBean should be(null)
            violation1.invalidValue should be("")

            val violation2 = sortedViolations[1]
            violation2.message should be("must be less than or equal to 3")
            violation2.propertyPath.toString() should be("UnicodeNameDataClass.winning-id")
            violation2.rootBeanClass should be(TestClasses.UnicodeNameDataClass::class.java)
            violation2.rootBean should be(null)
            violation2.invalidValue should be(5)
        }

        test("ExecutableValidator#validateConstructorParameters 3") {
            val constructor = getConstructor(
                TestClasses.Page::class.java,
                List::class.java,
                Int::class.java,
                Long::class.javaObjectType, // nullable means object
                Long::class.javaObjectType  // nullable means object
            )
            val violations: Set<ConstraintViolation<TestClasses.Page<*>>> =
                executableValidator.validateConstructorParameters(
                    constructor,
                    arrayOf(
                        arrayOf("foo", "bar"),
                        0 as Any,
                        1,
                        2
                    )
                )
            violations.size shouldBeEqual 1
            val violation = violations.first()
            violation.propertyPath.toString() should be("Page.pageSize")
            violation.message should be("must be greater than or equal to 1")
            violation.rootBeanClass should be(TestClasses.Page::class.java)
            violation.rootBean should be(null)
            violation.invalidValue should be(0)
        }

        test("ExecutableValidator#validateConstructorReturnValue") {
            val car = TestClasses.CarWithPassengerCount(
                listOf(
                    TestClasses.Person("abcd1234", "J Doe", DefaultAddress),
                    TestClasses.Person("abcd1235", "K Doe", DefaultAddress),
                    TestClasses.Person("abcd1236", "L Doe", DefaultAddress),
                    TestClasses.Person("abcd1237", "M Doe", DefaultAddress),
                    TestClasses.Person("abcd1238", "N Doe", DefaultAddress)
                )
            )
            val constructor =
                getConstructor(TestClasses.CarWithPassengerCount::class.java, List::class.java)
            val violations: Set<ConstraintViolation<TestClasses.CarWithPassengerCount>> =
                executableValidator.validateConstructorReturnValue(
                    constructor,
                    car
                )
            violations.size shouldBeEqual 1

            val violation = violations.first()
            violation.message should be("number of passenger(s) is not valid")
            violation.propertyPath.toString() should be("CarWithPassengerCount.<return value>")
            violation.rootBeanClass should be(TestClasses.CarWithPassengerCount::class.java)
            violation.rootBean should be(car)
            violation.invalidValue should be(car)
        }

        test("ExecutableValidator#cross-parameter") {
            val rentalStation = TestClasses.RentalStation("Hertz")
            val method =
                getMethod(
                    TestClasses.RentalStation::class.java,
                    "reserve",
                    LocalDate::class.java,
                    LocalDate::class.java
                )

            val start = LocalDate.now().plusDays(1)
            val end = LocalDate.now()
            val violations = executableValidator.validateParameters(
                rentalStation,
                method,
                arrayOf(start, end)
            )
            violations.size shouldBeEqual 1
            val violation = violations.first()
            violation.message should be("start is not before end")
            violation.propertyPath.toString() should be("reserve.<cross-parameter>")
            violation.rootBeanClass should be(TestClasses.RentalStation::class.java)
            violation.rootBean should be(rentalStation)
            violation.leafBean should be(rentalStation)
        }
    }

    private fun getMethod(
        clazz: Class<*>,
        methodName: String,
        vararg parameterTypes: Class<*>
    ): Method = clazz.getDeclaredMethod(methodName, *parameterTypes)

    private fun <T : Any> getConstructor(clazz: Class<T>, vararg parameterTypes: Class<*>): Constructor<T> =
        clazz.getDeclaredConstructor(*parameterTypes)
}
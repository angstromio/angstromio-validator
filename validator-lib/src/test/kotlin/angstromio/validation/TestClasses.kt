package angstromio.validation

import angstromio.validation.constraints.ConsistentDateParameters
import angstromio.validation.constraints.CountryCode
import angstromio.validation.constraints.InvalidConstraint
import angstromio.validation.constraints.OneOf
import angstromio.validation.constraints.StateConstraint
import angstromio.validation.constraints.StateConstraintPayload
import angstromio.validation.constraints.UUID
import angstromio.validation.constraints.ValidPassengerCount
import angstromio.validation.constraints.ValidPassengerCountReturnValue
import angstromio.validation.engine.MethodValidationResult
import com.fasterxml.jackson.annotation.JsonCreator
import jakarta.validation.Payload
import jakarta.validation.Valid
import jakarta.validation.ValidationException
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Future
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Past
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.beans.BeanProperty
import java.time.LocalDate

object OuterObject {

    data class NestedDataClass(val id: Long)

    object InnerObject {

        data class SuperNestedDataClass(@Min(5) val id: Long)
    }
}

object testclasses {

    /**
     * Represents the navigation path from an object to another in an object graph.
     */
    data class TestPath(val names: List<String>) {
        companion object {
            /** An empty path (no names) */
            val Empty: TestPath = TestPath(emptyList())

            /** Creates a new path from the given name */
            operator fun invoke(name: String): TestPath = TestPath(listOf(name))
        }
    }

    data class Page<T>(
        val data: List<T>,
        @Min(1) val pageSize: Int,
        val next: Long?,
        val previous: Long?
    )

    data class UnicodeNameDataClass(@Max(3) val `winning-id`: Int, @NotEmpty val name: String)

    class EvaluateConstraints

    interface NotADataClassTrait {
        @UUID
        fun uuid(): String
    }

    class NotADataClass(
        @NotNull val manufacturer: String?,
        @NotNull @Size(min = 2, max = 14) val licensePlate: String,
        @Min(2) val seatCount: Int,
        val uuid: String
    )

    data class WithFinalValField(
        @UUID val uuid: String,
        @NotEmpty val first: String?,
        val last: String?,
        private val permissions: Set<Int>?
    )

    data class WithBogusCascadedField(
        @NotNull @Size(min = 2, max = 14) val licensePlate: String,
        @Valid val ints: List<Int>
    )

    data class WithMetaFieldAnnotation(@field:NotEmpty val id: String)

    // @get does not affect instantiation
    data class WithMetaGetAnnotation(@get:NotEmpty val id: String)

    // @set affects instantiation
    data class WithMetaSetAnnotation(@set:NotEmpty var id: String)

    data class WithCascadingSetAnnotation(@set:Valid var customers: List<Customer>)

    // @NotEmpty is copied to generated field, but @Size is not. We should find both.
    data class WithPartialMetaFieldAnnotation(@field:NotEmpty @Size(min = 2, max = 10) val id: String)

    data class PathNotEmpty(@NotEmpty val path: TestPath, @NotEmpty val id: String)

    data class Customer(@NotEmpty val first: String, val last: String)

    data class AlwaysFails(@InvalidConstraint val name: String)

    data class AlwaysFailsMethodValidation(@NotEmpty val id: String) {
        @MethodValidation(fields = ["id"])
        fun checkId(): MethodValidationResult {
            throw ValidationException("oh noes!")
        }
    }

    // throws a ConstraintDefinitionException as `@MethodValidation` must not define args
    data class WithIncorrectlyDefinedMethodValidation(@NotEmpty val id: String) {
        @MethodValidation(fields = ["id"])
        fun checkId(a: String): MethodValidationResult =
            MethodValidationResult.validIfTrue({ a == id }, { "id not equal" })
    }

    // throws a ConstraintDefinitionException as `@MethodValidation` must return a MethodValidationResult
    data class AnotherIncorrectlyDefinedMethodValidation(@NotEmpty val id: String) {
        @MethodValidation(fields = ["id"])
        fun checkId(): Boolean = id.isNotEmpty()
    }

    data class SmallCar(
        @NotNull val manufacturer: String?,
        @NotNull @Size(min = 2, max = 14) val licensePlate: String,
        @Min(2) val seatCount: Int
    )

    interface RentalStationMixin {
        @Min(10)
        fun name(): String
    }

    // doesn't define any field
    interface RandoMixin

    data class RentalStation(@NotNull val name: String) {
        fun rentCar(
            @NotNull customer: Customer,
            @NotNull @Future start: LocalDate,
            @Min(1) duration: Int
        ): Unit = TODO()

        fun updateCustomerRecords(
            @NotEmpty @Size(min = 1) @Valid customers: List<Customer>
        ): Unit = TODO()

        @NotEmpty
        @Size(min = 1)
        fun getCustomers(): List<Customer> = TODO()

        fun listCars(@NotEmpty `airport-code`: String): List<String> = TODO()

        @ConsistentDateParameters
        fun reserve(start: LocalDate, end: LocalDate): Boolean = TODO()
    }

    interface OtherCheck
    interface PersonCheck

    data class MinIntExample(@Min(1) val numberValue: Int)

    // CountryCode
    data class CountryCodeExample(@CountryCode val countryCode: String)
    data class CountryCodeOptionExample(@CountryCode val countryCode: String?)
    data class CountryCodeListExample(@CountryCode val countryCode: List<String>)
    data class CountryCodeOptionListExample(@CountryCode val countryCode: List<String>?)
    data class CountryCodeArrayExample(@CountryCode val countryCode: Array<String>) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as CountryCodeArrayExample

            return countryCode.contentEquals(other.countryCode)
        }

        override fun hashCode(): Int {
            return countryCode.contentHashCode()
        }
    }

    data class CountryCodeOptionArrayExample(@CountryCode val countryCode: Array<String>?) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as CountryCodeOptionArrayExample

            if (countryCode != null) {
                if (other.countryCode == null) return false
                if (!countryCode.contentEquals(other.countryCode)) return false
            } else if (other.countryCode != null) return false

            return true
        }

        override fun hashCode(): Int {
            return countryCode?.contentHashCode() ?: 0
        }
    }

    data class CountryCodeInvalidTypeExample(@CountryCode val countryCode: Long)
    data class CountryCodeOptionInvalidTypeExample(@CountryCode val countryCode: Long?)

    // OneOf
    data class OneOfExample(@OneOf(value = ["a", "B", "c"]) val enumValue: String)
    data class OneOfOptionExample(@OneOf(value = ["a", "B", "c"]) val enumValue: String?)
    data class OneOfListExample(@OneOf(value = ["a", "B", "c"]) val enumValue: List<String>)
    data class OneOfOptionListExample(@OneOf(value = ["a", "B", "c"]) val enumValue: List<String>?)
    data class OneOfInvalidTypeExample(@OneOf(value = ["a", "B", "c"]) val enumValue: Long)
    data class OneOfOptionInvalidTypeExample(@OneOf(value = ["a", "B", "c"]) val enumValue: Long?)

    // UUID
    data class UUIDExample(@UUID val uuid: String)
    data class UUIDOptionExample(@UUID val uuid: String?)

    data class NestedOptionExample(
        @NotEmpty val id: String,
        val name: String,
        @Valid val user: User?
    )

    // multiple annotations
    data class User(
        @NotEmpty val id: String,
        val name: String,
        @OneOf(value = ["F", "M", "Other"]) val gender: String
    ) {
        @MethodValidation(fields = ["name"])
        fun nameCheck(): MethodValidationResult =
            MethodValidationResult.validIfTrue({ name.isNotEmpty() }, { "cannot be empty" })
    }

    // nested NotEmpty on a collection of a data class type
    data class Users(@NotEmpty @Valid val users: List<User>)

    // Other fields not in executable
    data class Person(@NotEmpty val id: String, @NotEmpty val name: String, @Valid val address: Address) {
        val company: String = "AngstromIO"
        val city: String = "San Francisco"
        val state: String = "California"

        @MethodValidation(fields = ["address"])
        fun validateAddress(): MethodValidationResult =
            MethodValidationResult.validIfTrue({ address.state.isNotEmpty() }, { "state must be specified" })
    }

    data class WithPersonCheck(
        @NotEmpty(groups = [PersonCheck::class]) val id: String,
        @NotEmpty val name: String
    )

    data class NoConstructorParams(val unused: String?) {
        @get:BeanProperty
        @NotEmpty
        var id: String = ""
    }

    data class AnnotatedInternalFields(
        @NotEmpty val id: String,
        @Size(min = 1, max = 100) val bigString: String
    ) {
        @NotEmpty
        val company: String = "" // this will be validated
    }

    data class AnnotatedBeanProperties(@Min(1) val numbers: Int) {
        @get:BeanProperty
        @NotEmpty
        var field1: String = "default"
    }

    data class Address(
        val line1: String,
        val line2: String? = null,
        val city: String,
        @StateConstraint(payload = [StateConstraintPayload::class]) val state: String,
        val zipcode: String,
        val additionalPostalCode: String? = null
    )

    // Customer defined validations
    data class StateValidationExample(@StateConstraint val state: String)

    // Integration test
    data class ValidateUserRequest(
        @NotEmpty @Pattern(regexp = "<a-z>+") val userName: String,
        @Max(value = 9999) val id: Long,
        val title: String
    )

    data class NestedUserPayload(val id: String, val job: String) : Payload
    data class NestedUser(
        @NotEmpty val id: String,
        @Valid val person: Person,
        @OneOf(value = ["F", "M", "Other"]) val gender: String,
        val job: String
    ) {
        @MethodValidation(fields = ["job"], payload = [NestedUserPayload::class])
        fun jobCheck(): MethodValidationResult =
            MethodValidationResult.validIfTrue(
                { job.isNotEmpty() },
                { "cannot be empty" },
                payload = NestedUserPayload(id, job)
            )
    }

    data class Division(
        val name: String,
        @Valid val team: List<Group>
    )

    data class Persons(
        @NotEmpty val id: String,
        @Min(1) @Valid val people: List<Person>
    )

    data class Group(
        @NotEmpty val id: String,
        @Min(1) @Valid val people: List<Person>
    )

    data class CustomerAccount(
        @NotEmpty val accountName: String,
        @Valid val customer: User?
    )

    data class DataClassWithBoxedPrimitives(val events: Int, val errors: Int)

    data class CollectionOfCollection(
        @NotEmpty val id: String,
        @Valid @Min(1) val people: List<Persons>
    )

    data class CollectionWithArray(
        @NotEmpty @Size(min = 1, max = 30) val names: Array<String>,
        @NotEmpty @Max(2) val users: Array<User>
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as CollectionWithArray

            if (!names.contentEquals(other.names)) return false
            if (!users.contentEquals(other.users)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = names.contentHashCode()
            result = 31 * result + users.contentHashCode()
            return result
        }
    }

    // cycle -- impossible to instantiate without using null to terminate somewhere
    data class A(@NotEmpty val id: String, @Valid val b: B)
    data class B(@NotEmpty val id: String, @Valid val c: C)
    data class C(@NotEmpty val id: String, @Valid val a: A?)

    // another cycle -- fails as we detect the D type in F
    data class D(@NotEmpty val id: String, @Valid val e: E)
    data class E(@NotEmpty val id: String, @Valid val f: F)
    data class F(@NotEmpty val id: String, @Valid val d: D?)

    // last one -- fails as we detect the G type in I
    data class G(@NotEmpty val id: String, @Valid val h: H)
    data class H(@NotEmpty val id: String, @Valid val i: I)
    data class I(@NotEmpty val id: String, @Valid val g: List<G>?)

    // no validation -- no annotations
    data class TestJsonCreator(val int: Int) {
        companion object {
            @JsonCreator
            operator fun invoke(s: String): TestJsonCreator = TestJsonCreator(s.toInt())
        }
    }

    // no validation -- no annotations
    data class TestJsonCreator2(val ints: List<Int>, val default: String = "Hello, World") {
        companion object {
            @JsonCreator
            operator fun invoke(strings: List<String>): TestJsonCreator2 =
                TestJsonCreator2(strings.map { it.toInt() })
        }
    }

    // no validation -- annotation is on a factory method "executable"
    data class TestJsonCreatorWithValidation(val int: Int) {
        companion object {
            @JsonCreator
            operator fun invoke(@NotEmpty s: String): TestJsonCreatorWithValidation =
                TestJsonCreatorWithValidation(s.toInt())
        }
    }

    // this should validate per the primary executable annotation
    data class TestJsonCreatorWithValidations(@OneOf(value = ["42", "137"]) val int: Int) {
        companion object {
            @JsonCreator
            operator fun invoke(@NotEmpty s: String): TestJsonCreatorWithValidations =
                TestJsonCreatorWithValidations(s.toInt())
        }
    }

    // no validation -- no annotations
    data class DataClassWithMultipleConstructors(val number1: Long, val number2: Long, val number3: Long) {
        constructor(
            numberAsString1: String,
            numberAsString2: String,
            numberAsString3: String
        ) : this(numberAsString1.toLong(), numberAsString2.toLong(), numberAsString3.toLong())
    }

    // no validation -- no annotations
    data class DataClassWithMultipleConstructorsAnnotated(
        val number1: Long,
        val number2: Long,
        val number3: Long
    ) {
        @JsonCreator
        constructor(
            numberAsString1: String,
            numberAsString2: String,
            numberAsString3: String
        ) : this(numberAsString1.toLong(), numberAsString2.toLong(), numberAsString3.toLong())
    }

    // no validation -- annotations are on secondary executable
    data class DataClassWithMultipleConstructorsAnnotatedAndValidations(
        val number1: Long,
        val number2: Long,
        val uuid: String
    ) {
        @JsonCreator
        constructor(
            @NotEmpty numberAsString1: String,
            @OneOf(value = ["10001", "20002", "30003"]) numberAsString2: String,
            @UUID thirdArgument: String
        ) : this(numberAsString1.toLong(), numberAsString2.toLong(), thirdArgument)
    }

    // this should validate -- annotations are on the primary executable
    data class DataClassWithMultipleConstructorsPrimaryAnnotatedAndValidations(
        @Min(10000) val number1: Long,
        @OneOf(value = ["10001", "20002", "30003"]) val number2: Long,
        @UUID val uuid: String
    ) {
        constructor(
            numberAsString1: String,
            numberAsString2: String,
            thirdArgument: String
        ) : this(numberAsString1.toLong(), numberAsString2.toLong(), thirdArgument)
    }

    interface AncestorWithValidation {
        @get:NotEmpty val field1: String

        @MethodValidation(fields = ["field1"])
        fun validateField1(): MethodValidationResult =
            MethodValidationResult.validIfTrue({
                try {
                    field1.toDouble(); true
                } catch (e: Exception) {
                    false
                }
            }, { "not a double value" })
    }

    data class ImplementsAncestor(override val field1: String) : AncestorWithValidation

    data class InvalidDoublePerson(@NotEmpty val name: String, @NotEmpty val otherName: () -> String)
    data class DoublePerson(@NotEmpty val name: String, @NotEmpty val otherName: () -> String)
    data class ValidDoublePerson(@NotEmpty val name: String, @NotEmpty val otherName: () -> String) {
        @MethodValidation
        fun checkOtherName(): MethodValidationResult =
            MethodValidationResult.validIfTrue(
                { otherName().length >= 3 },
                { "otherName must be longer than 3 chars" })
    }

    data class PossiblyValidDoublePerson(@NotEmpty val name: String, @NotEmpty val otherName: () -> String) {
        fun referenceSecondArgInMethod(): String = "The otherName value is $otherName"
    }

    data class DataClassWithIntAndLocalDate(
        @NotEmpty val name: String,
        val age: Int,
        val age2: Int,
        val age3: Int,
        val dateTime: LocalDate,
        val dateTime2: LocalDate,
        val dateTime3: LocalDate,
        val dateTime4: LocalDate,
        @NotEmpty val dateTime5: LocalDate?
    )

    data class SimpleCar(
        val id: Long,
        val make: CarMake,
        val model: String,
        @Min(2000) val year: Int,
        @NotEmpty @Size(min = 2, max = 14) val licensePlate: String, //multiple annotations
        @Min(0) val numDoors: Int = 4,
        val manual: Boolean = false
    )

    // executable annotation -- note the validator must specify a `@SupportedValidationTarget` of annotated element
    data class CarWithPassengerCount @ValidPassengerCountReturnValue(max = 1) constructor(val passengers: List<Person>)

    // class-level annotation
    @ValidPassengerCount(max = 4)
    data class Car(
        val id: Long,
        val make: CarMake,
        val model: String,
        @Min(2000) val year: Int,
        @Valid val owners: List<Person>,
        @NotEmpty @Size(min = 2, max = 14) val licensePlate: String, //multiple annotations
        @Min(0) val numDoors: Int = 4,
        val manual: Boolean = false,
        val ownershipStart: LocalDate/* = LocalDate.now()*/,
        val ownershipEnd: LocalDate/* = LocalDate.now().plusYears(1)*/,
        val warrantyStart: LocalDate?/* = null*/,
        val warrantyEnd: LocalDate?/* = null*/,
        @Valid val passengers: List<Person>/* = emptyList()*/
    ) {

        @MethodValidation
        fun validateId(): MethodValidationResult =
            MethodValidationResult.validIfTrue({ id.mod(2) == 1 }, { "id may not be even" })

        @MethodValidation
        fun validateYearBeforeNow(): MethodValidationResult {
            val thisYear = LocalDate.now().year
            val yearMoreThanOneYearInFuture: Boolean =
                if (year > thisYear) {
                    (year - thisYear) > 1
                } else false
            return MethodValidationResult.validIfFalse(
                { yearMoreThanOneYearInFuture },
                { "Model year can be at most one year newer." }
            )
        }

        @MethodValidation(fields = ["ownershipEnd"])
        fun ownershipTimesValid(): MethodValidationResult =
            validateTimeRange(
                ownershipStart,
                ownershipEnd,
                "ownershipStart",
                "ownershipEnd"
            )

        @MethodValidation(fields = ["warrantyStart", "warrantyEnd"])
        fun warrantyTimeValid(): MethodValidationResult =
            validateTimeRange(
                warrantyStart,
                warrantyEnd,
                "warrantyStart",
                "warrantyEnd"
            )
    }

    data class Driver(
        @Valid val person: Person,
        @Valid val car: Car
    )

    data class MultipleConstraints(
        @NotEmpty @Max(100) val ints: List<Int>,
        @NotEmpty @Pattern(regexp = "\\d") val digits: String,
        @NotEmpty @Size(min = 3, max = 3) @OneOf(value = ["how", "now", "brown", "cow"]) val strings: List<String>
    )

    // maps not supported
    data class MapsAndMaps(
        @NotEmpty val id: String,
        @AssertTrue val bool: Boolean,
        @Valid val carAndDriver: Map<Person, SimpleCar>
    )

    // @Valid only visible on field not on type arg
    data class TestAnnotations(
        @NotEmpty val cars: List<@Valid SimpleCar>
    )

    data class GenericTestDataClass<T>(@NotEmpty @Valid val data: T)
    data class GenericMinTestDataClass<T>(@Min(4) val data: T)
    data class GenericTestDataClassMultipleTypes<T, U, V>(
        @NotEmpty @Valid val data: T,
        @Size(max = 100) val things: List<U>,
        @Size(min = 3, max = 3) @Valid val otherThings: List<V>
    )

    data class GenericTestDataClassWithMultipleArgs<T>(
        @NotEmpty val data: T,
        @Min(5) val number: Int
    )

    fun validateTimeRange(
        startTime: LocalDate?,
        endTime: LocalDate?,
        startTimeProperty: String,
        endTimeProperty: String
    ): MethodValidationResult {
        val rangeDefined = startTime != null && endTime != null
        val partialRange = !rangeDefined && (startTime != null || endTime != null)

        return if (rangeDefined) {
            MethodValidationResult.validIfTrue(
                { startTime!!.isBefore(endTime) },
                {
                    "%s <%s> must be after %s <%s>".format(
                        endTimeProperty,
                        endTime!!.toEpochDay(),
                        startTimeProperty,
                        startTime!!.toEpochDay()
                    )
                }
            )
        } else if (partialRange) {
            MethodValidationResult.Invalid(
                "both %s and %s are required for a valid range".format(startTimeProperty, endTimeProperty)
            )
        } else {
            MethodValidationResult.Valid
        }
    }

    data class PersonWithLogging(
        val id: Int,
        val name: String,
        val age: Int?,
        val age_with_default: Int? = null,
        val nickname: String = "unknown"
    ) {
        val logger: Logger = LoggerFactory.getLogger(PersonWithLogging::class.java)
    }

    data class UsersRequest(
        @Max(100) @DoesNothing val max: Int,
        @Past @DoesNothing val startDate: LocalDate?,
        @DoesNothing val verbose: Boolean = false
    )

    data class WithSecondaryConstructor(@Min(10) val one: Int, val two: Int) {
        constructor(three: String, @NotBlank four: String) : this(three.toInt(), four.toInt())
    }

    data class AlwaysFailMethodValidation(val id: String) {
        @MethodValidation(fields = ["id"])
        fun checkId(): MethodValidationResult = throw RuntimeException("FORCED TEST EXCEPTION")
    }
}
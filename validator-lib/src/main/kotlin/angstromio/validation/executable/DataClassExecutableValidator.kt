package angstromio.validation.executable

import angstromio.validation.metadata.ExecutableDescriptor
import jakarta.validation.ConstraintViolation
import jakarta.validation.ValidationException
import kotlin.reflect.KCallable
import kotlin.reflect.KClass
import kotlin.reflect.KFunction

interface DataClassExecutableValidator {

    /**
     * Validates all methods annotated with `@MethodValidation` of the given object.
     *
     * @param obj the object on which the methods to validate are invoked.
     * @param groups the list of groups targeted for validation (defaults to Default).
     * @param T the type hosting the methods to validate.
     *
     * @return a set with the constraint violations caused by this validation; will be empty if no error occurs, but never null.
     *
     * @throws IllegalArgumentException - if null is passed for the object to validate.
     * @throws ValidationException      - if a non-recoverable error happens during the validation process.
     */
    @Throws(ValidationException::class)
    fun <T : Any> validateMethods(
        obj: T,
        vararg groups: KClass<*>
    ): Set<ConstraintViolation<T>>

    /**
     * Validates the given method annotated with `@MethodValidation` of the given object.
     *
     * @param obj the object on which the method to validate is invoked.
     * @param method the `@MethodValidation` annotated method to invoke for validation.
     * @param groups the list of groups targeted for validation (defaults to Default).
     * @param T the type hosting the method to validate.
     *
     * @return a set with the constraint violations caused by this validation; will be empty if no error occurs, but never null.
     *
     * @throws IllegalArgumentException - if null is passed for the object to validate or if the given method is not a method of the object class type.
     * @throws ValidationException      - if a non-recoverable error happens during the validation process.
     */
    @Throws(ValidationException::class)
    fun <T : Any> validateMethod(
        obj: T,
        method: KFunction<*>,
        vararg groups: KClass<*>
    ): Set<ConstraintViolation<T>>

    /**
     * Validates all constraints placed on the parameters of the given method.
     *
     * @param obj the object on which the method to validate is invoked.
     * @param method the method for which the parameter constraints is validated.
     * @param parameterValues the values provided by the caller for the given method's parameters.
     * @param groups the list of groups targeted for validation (defaults to Default).
     * @param T the type hosting the method to validate.
     *
     * @return a set with the constraint violations caused by this validation; will be empty if no error occurs, but never null.
     *
     * @throws IllegalArgumentException - if null is passed for any of the parameters or if parameters don't match with each other.
     * @throws ValidationException      - if a non-recoverable error happens during the validation process.
     */
    @Throws(ValidationException::class)
    fun <T : Any> validateParameters(
        obj: T,
        method: KFunction<*>,
        parameterValues: List<Any?>,
        vararg groups: KClass<*>
    ): Set<ConstraintViolation<T>>

    /**
     * Validates all return value constraints of the given method.
     *
     * @param obj the object on which the method to validate is invoked.
     * @param method the method for which the return value constraints is validated.
     * @param returnValue the value returned by the given method.
     * @param groups the list of groups targeted for validation (defaults to Default).
     * @param T the type hosting the method to validate.
     *
     * @return a set with the constraint violations caused by this validation; will be empty if no error occurs, but never null.
     *
     * @throws IllegalArgumentException - if null is passed for any of the parameters or if parameters don't match with each other.
     * @throws ValidationException      - if a non-recoverable error happens during the validation process.
     */
    @Throws(ValidationException::class)
    fun <T : Any> validateReturnValue(
        obj: T,
        method: KFunction<*>,
        returnValue: Any,
        vararg groups: KClass<*>
    ): Set<ConstraintViolation<T>>

    /**
     * Validates all constraints placed on the parameters of the given constructor.
     *
     * @param constructor the constructor for which the parameter constraints is validated.
     * @param parameterValues the values provided by the caller for the given constructor's parameters.
     * @param groups the list of groups targeted for validation (defaults to Default).
     * @param T the type hosting the constructor to validate.
     *
     * @return a set with the constraint violations caused by this validation; Will be empty if no error occurs, but never null.
     *
     * @throws IllegalArgumentException - if null is passed for any of the parameters or if parameters don't match with each other.
     * @throws ValidationException      - if a non-recoverable error happens during the validation process.
     */
    @Throws(ValidationException::class)
    fun <T : Any> validateConstructorParameters(
        constructor: KFunction<T>,
        parameterValues: List<Any?>,
        vararg groups: KClass<*>
    ): Set<ConstraintViolation<T>>

    /**
     * Validates all constraints placed on the parameters of the given method.
     *
     * @param method the method for which the parameter constraints is validated.
     * @param parameterValues the values provided by the caller for the given method's parameters.
     * @param groups the list of groups targeted for validation (defaults to Default).
     * @param R the return type the method to validate.
     *
     * @return a set with the constraint violations caused by this validation; Will be empty if no error occurs, but never null.
     *
     * @throws IllegalArgumentException - if null is passed for any of the parameters or if parameters don't match with each other.
     * @throws ValidationException      - if a non-recoverable error happens during the validation process.
     */
    @Throws(ValidationException::class)
    fun <R : Any> validateMethodParameters(
        method: KFunction<R>,
        parameterValues: List<Any?>,
        vararg groups: KClass<*>
    ): Set<ConstraintViolation<R>>

    /**
     * Validates all return value constraints of the given constructor.
     *
     * @param constructor the constructor for which the return value constraints is validated.
     * @param createdObject the object instantiated by the given method.
     * @param groups the list of groups targeted for validation (defaults to Default).
     * @param T the type hosting the constructor to validate.
     *
     * @return a set with the constraint violations caused by this validation; will be empty, if no error occurs, but never null.
     *
     * @throws IllegalArgumentException - if null is passed for any of the parameters or if parameters don't match with each other.
     * @throws ValidationException      - if a non-recoverable error happens during the validation process.
     */
    @Throws(ValidationException::class)
    fun <T : Any> validateConstructorReturnValue(
        constructor: KFunction<T>,
        createdObject: T,
        vararg groups: KClass<*>
    ): Set<ConstraintViolation<T>>

    /**
     * Validates all constraints placed on the parameters of the given executable.
     *
     * @param executable the <ExecutableDescriptor> for which the parameter constraints is validated.
     * @param parameterValues the values provided by the caller for the given executable's parameters.
     * @param parameterNames the parameter names to use for error reporting.
     * @param groups the list of groups targeted for validation (defaults to Default).
     * @param T the type defining the executable to validate.
     *
     * @return a set with the constraint violations caused by this validation; Will be empty if no error occurs, but never null.
     *
     * @throws IllegalArgumentException - if null is passed for any of the parameters or if parameters don't match with each other.
     * @throws ValidationException      - if a non-recoverable error happens during the validation process.
     */
    fun <T : Any> validateExecutableParameters(
        executable: ExecutableDescriptor<T>,
        parameterValues: List<Any?>,
        parameterNames: List<String>,
        vararg groups: KClass<*>
    ): Set<ConstraintViolation<T>>

    /**
     * Validates the `@MethodValidation` placed constraints on the given array of descriptors.
     * @param methods the array of <MethodDescriptor> instances to evaluate.
     * @param obj the object on which the method to validate is invoked.
     * @param groups the list of groups targeted for validation (defaults to Default).
     * @param T the type defining the methods to validate.
     *
     * @return a set with the constraint violations caused by this validation; Will be empty if no error occurs, but never null.
     *
     * @throws IllegalArgumentException - if null is passed for any of the parameters or if parameters don't match with each other.
     * @throws ValidationException      - if a non-recoverable error happens during the validation process.
     */
    fun <T : Any> validateMethods(
        methods: List<ExecutableDescriptor<Any>>,
        obj: T,
        vararg groups: KClass<*>
    ): Set<ConstraintViolation<T>>
}
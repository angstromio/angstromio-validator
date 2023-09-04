package angstromio.validation

import angstromio.util.control.NonFatal
import angstromio.util.extensions.Nulls.mapNotNull
import angstromio.util.extensions.find
import angstromio.util.extensions.notEq
import angstromio.validation.cfg.ConstraintMapping
import angstromio.validation.engine.MethodValidationResult
import angstromio.validation.executable.DataClassExecutableValidator
import angstromio.validation.internal.Types
import angstromio.validation.internal.ValidationContext
import angstromio.validation.internal.constraintvalidation.ConstraintValidatorContextFactory
import angstromio.validation.internal.engine.ConstraintViolationFactory
import angstromio.validation.internal.metadata.descriptor.ConstraintDescriptorFactory
import angstromio.validation.internal.metadata.descriptor.MethodValidationConstraintDescriptor
import angstromio.validation.metadata.DataClassDescriptor
import angstromio.validation.metadata.Descriptor
import angstromio.validation.metadata.ExecutableDescriptor
import angstromio.validation.metadata.PropertyDescriptor
import arrow.core.memoize
import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.ConstraintViolation
import jakarta.validation.MessageInterpolator
import jakarta.validation.Payload
import jakarta.validation.UnexpectedTypeException
import jakarta.validation.Validation
import jakarta.validation.ValidationException
import jakarta.validation.Validator
import jakarta.validation.constraintvalidation.SupportedValidationTarget
import jakarta.validation.constraintvalidation.ValidationTarget
import jakarta.validation.groups.Default
import jakarta.validation.metadata.ConstraintDescriptor
import jakarta.validation.spi.ValidationProvider
import org.hibernate.validator.HibernateValidator
import org.hibernate.validator.HibernateValidatorConfiguration
import org.hibernate.validator.internal.engine.ValidatorFactoryImpl
import org.hibernate.validator.internal.engine.ValidatorFactoryInspector
import org.hibernate.validator.internal.engine.constraintvalidation.ConstraintValidatorManager
import org.hibernate.validator.internal.engine.path.PathImpl
import org.hibernate.validator.internal.metadata.aggregated.CascadingMetaDataBuilder
import org.hibernate.validator.internal.metadata.aggregated.ExecutableMetaData
import org.hibernate.validator.internal.metadata.descriptor.ConstraintDescriptorImpl
import org.hibernate.validator.internal.metadata.raw.ConfigurationSource
import org.hibernate.validator.internal.metadata.raw.ConstrainedElement
import org.hibernate.validator.internal.metadata.raw.ConstrainedExecutable
import org.hibernate.validator.internal.metadata.raw.ConstrainedParameter
import org.hibernate.validator.internal.properties.javabean.JavaBeanFactory
import org.hibernate.validator.internal.util.annotation.AnnotationDescriptor
import org.hibernate.validator.internal.util.annotation.AnnotationFactory
import java.lang.reflect.InvocationTargetException
import java.util.*
import kotlin.reflect.KCallable
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.jvm.javaConstructor
import kotlin.reflect.jvm.javaMethod
import kotlin.reflect.jvm.jvmErasure

typealias ConstraintValidatorType = ConstraintValidator<in Annotation, *>

class DataClassValidator(
    private val cacheSize: Long,
    private val validatorFactory: ValidatorFactoryInspector,
    internal val underlying: Validator
) : DataClassExecutableValidator {

    companion object {
        /** The size of the caffeine cache that is used to store reflection data on a validated data class. */
        private const val DEFAULT_DESCRIPTOR_CACHE_SIZE: Long = 128

        fun builder(): Builder = Builder()

        operator fun invoke(): DataClassValidator = builder().validator()
    }

    class Builder(
        internal val descriptorCacheSize: Long = DEFAULT_DESCRIPTOR_CACHE_SIZE,
        private val messageInterpolator: MessageInterpolator? = null,
        private val constraintMappings: Set<ConstraintMapping> = emptySet()
    ) {

        fun withDescriptorCacheSize(size: Long): Builder =
            Builder(
                descriptorCacheSize = size,
                messageInterpolator = this.messageInterpolator,
                constraintMappings = this.constraintMappings
            )

        fun withMessageInterpolator(messageInterpolator: MessageInterpolator): Builder =
            Builder(
                descriptorCacheSize = this.descriptorCacheSize,
                messageInterpolator = messageInterpolator,
                constraintMappings = this.constraintMappings
            )

        fun withConstraintMappings(constraintMappings: Set<ConstraintMapping>): Builder =
            Builder(
                descriptorCacheSize = this.descriptorCacheSize,
                messageInterpolator = this.messageInterpolator,
                constraintMappings = constraintMappings
            )

        /**
         * Register a [ConstraintMapping].
         * @note REPLACES any other previously added mappings.
         */
        fun withConstraintMapping(constraintMapping: ConstraintMapping): Builder =
            Builder(
                descriptorCacheSize = this.descriptorCacheSize,
                messageInterpolator = this.messageInterpolator,
                constraintMappings = setOf(constraintMapping)
            )

        @Suppress("UNCHECKED_CAST")
        fun validator(): DataClassValidator {
            val configuration: HibernateValidatorConfiguration =
                Validation
                    .byProvider(HibernateValidator::class.java as Class<ValidationProvider<HibernateValidatorConfiguration>>)
                    .configure()
            // add user-configured message interpolator
            messageInterpolator?.let { interpolator ->
                configuration.messageInterpolator(interpolator)
            }
            // add user-configured constraint mappings
            constraintMappings.forEach { constraintMapping ->
                val hibernateConstraintMapping: org.hibernate.validator.cfg.ConstraintMapping =
                    configuration.createConstraintMapping()
                hibernateConstraintMapping
                    .constraintDefinition(constraintMapping.annotationClazz.java as Class<Annotation>)
                    .includeExistingValidators(constraintMapping.includeExistingValidators)
                    .validatedBy(constraintMapping.constraintValidator.java as Class<ConstraintValidator<Annotation, *>>)
                configuration.addMapping(hibernateConstraintMapping)
            }

            val validatorFactory = configuration.buildValidatorFactory()
            return DataClassValidator(
                cacheSize = this.descriptorCacheSize,
                ValidatorFactoryInspector(validatorFactory as ValidatorFactoryImpl),
                validatorFactory.validator
            )
        }
    }

    internal val descriptorFactory: DescriptorFactory =
        DescriptorFactory(cacheSize, validatorFactory.constraintHelper())

    private val constraintViolationFactory: ConstraintViolationFactory = ConstraintViolationFactory(validatorFactory)

    private val constraintDescriptorFactory: ConstraintDescriptorFactory = ConstraintDescriptorFactory(validatorFactory)

    private val constraintValidatorContextFactory: ConstraintValidatorContextFactory =
        ConstraintValidatorContextFactory(validatorFactory)

    private val constraintValidatorManager: ConstraintValidatorManager =
        validatorFactory.getConstraintCreationContext().constraintValidatorManager

    fun close() {
        validatorFactory.close()
    }

    /**
     * Returns a [DataClassDescriptor] object describing the given data class type constraints.
     * Descriptors describe constraints on a given class and any cascaded data class types.
     *
     * The returned object (and associated objects including DataClassDescriptor) are immutable.
     *
     * @param clazz class or interface type evaluated
     * @param T the clazz type
     *
     * @return the [DataClassDescriptor] for the specified class
     *
     * @see [DataClassDescriptor]
     */
    fun <T : Any> getConstraintsForClass(clazz: KClass<T>): DataClassDescriptor<T> = descriptorFactory.describe(clazz)

    /**
     * Returns a [ExecutableDescriptor] object describing a given [KCallable].
     *
     * The returned object (and associated objects including ExecutableDescriptors) are immutable.
     *
     * @param executable the [KCallable] to describe.
     *
     * @return the [ExecutableDescriptor] for the specified [KCallable]
     *
     * @note the returned [ExecutableDescriptor] is NOT cached. It is up to the caller of this
     *       method to optimize any calls to this method.
     */
    fun <R : Any> describeExecutable(
        executable: KFunction<R>, mixinClazz: KClass<*>?
    ): ExecutableDescriptor<R>? =
        descriptorFactory.describe(executable, mixinClazz)

    /**
     * Returns a list of [ExecutableDescriptor] instances describing the `@MethodValidation`-annotated
     * or otherwise constrained methods of the given data class type.
     *
     * @param clazz class or interface type evaluated
     *
     * @return a list of [ExecutableDescriptor] for `@MethodValidation`-annotated or otherwise
     *         constrained methods of the class
     *
     * @note the returned [ExecutableDescriptor] instances are NOT cached. It is up to the caller of this
     *       method to optimize any calls to this method.
     */
    fun describeMethods(clazz: KClass<*>): List<ExecutableDescriptor<Any>> = descriptorFactory.describeMethods(clazz)

    /**
     * Returns the set of Constraints which validate the given [Annotation] class.
     *
     * For instance if the constraint type is `Class<angstromio.validation.constraints.CountryCode>`,
     * the returned set would contain an instance of the `ISO3166CountryCodeConstraintValidator`.
     *
     * @return the set of supporting constraint validators for a given [Annotation].
     * @note this method is memoized as it should only ever need to be calculated once for a given [Class].
     */
    val findConstraintValidators = ::findConstraintValidatorsFn.memoize()
    private fun findConstraintValidatorsFn(annotationClazz: (KClass<out Annotation>)): Set<ConstraintValidator<*, *>> {
        // compute from constraint, otherwise compute from registry
        val validatedBy = if (annotationClazz.java.isAnnotationPresent(Constraint::class.java)) {
            val constraintAnnotation = annotationClazz.java.getAnnotation(Constraint::class.java)
            constraintAnnotation.validatedBy.map { clazz ->
                validatorFactory.constraintValidatorFactory().getInstance(clazz.java) ?: throw ValidationException(
                    "Constraint factory returned null when trying to create instance of $clazz."
                )
            }.toSet()
        } else emptySet()
        return validatedBy.ifEmpty {
            validatorFactory.constraintHelper().getAllValidatorDescriptors(annotationClazz.java)
                .map { it.newInstance(validatorFactory.constraintValidatorFactory()) }.toSet()
        }
    }

    /**
     * Checks whether the specified [[Annotation]] is a valid constraint. A constraint has to fulfill the
     * following conditions:
     *
     *   - Must be annotated with [[jakarta.validation.Constraint]]
     *   - Define a `message` parameter
     *   - Define a `groups` parameter
     *   - Define a `payload` parameter
     *
     * @param annotation The [[Annotation]] to test.
     *
     * @return true if the constraint fulfills the above conditions, false otherwise.
     */
    fun isConstraintAnnotation(annotation: Annotation): Boolean = isConstraintAnnotation(annotation.annotationClass)

    /**
     * Checks whether the specified [[Annotation]] clazz is a valid constraint. A constraint has to fulfill the
     * following conditions:
     *
     *   - Must be annotated with [[jakarta.validation.Constraint]]
     *   - Define a `message` parameter
     *   - Define a `groups` parameter
     *   - Define a `payload` parameter
     *
     * @return true if the constraint fulfills the above conditions, false otherwise.
     * @note this method is memoized as it should only ever need to be calculated once for a given [[Class]].
     */
    val isConstraintAnnotation = ::isConstraintAnnotationClazz.memoize()
    private fun isConstraintAnnotationClazz(clazz: KClass<out Annotation>): Boolean =
        validatorFactory.constraintHelper().isConstraintAnnotation(clazz.java)

    /**
     * Validates all constraint constraints on an object.
     *
     * @param obj the object to validate.
     *
     * @throws ValidationException      - if any constraints produce a violation.
     * @throws IllegalArgumentException - if object is null.
     */
    @Throws(ValidationException::class)
    fun verify(
        obj: Any, vararg groups: KClass<*>
    ) {
        val invalidResult = validate(obj, *groups)
        if (invalidResult.isNotEmpty()) throw constraintViolationFactory.newConstraintViolationException(invalidResult)
    }

    /**
     * Validates all constraint constraints on an object.
     *
     * @param obj    the object to validate.
     * @param groups the list of groups targeted for validation (defaults to Default).
     * @param T the type of the object to validate.
     *
     * @return constraint violations or an empty set if none
     *
     * @throws IllegalArgumentException - if object is null.
     */
    fun <T : Any> validate(
        obj: T, vararg groups: KClass<*>
    ): Set<ConstraintViolation<T>> {
        val context = ValidationContext<T>(null, null, null, null, PathImpl.createRootPath())
        return validate(maybeDescriptor = null, context = context, value = obj, groups = groups.map { it.java })
    }

    /**
     * Validates all constraint constraints on the `fieldName` field of the given class `beanType`
     * if the `fieldName` field value were `value`.
     *
     * ConstraintViolation objects return `null` for ConstraintViolation.getRootBean() and
     * ConstraintViolation.getLeafBean().
     *
     * ==Usage==
     * Validate value of "" for the "id" field in data class `MyDataClass` (enabling group "Checks"):
     *
     *      data class MyDataClass(@NotEmpty(groups = Checks::class) id)
     *      validator.validateFieldValue(MyDataClass::class, "id", "", List(Checks::class))
     *
     *
     * @param beanType     the data class type.
     * @param propertyName field to validate.
     * @param value        field value to validate.
     * @param groups       the list of groups targeted for validation (defaults to Default).
     * @param T the type of the object to validate
     *
     * @return constraint violations or an empty set if none
     *
     * @throws IllegalArgumentException - if beanType is null, if fieldName is null, empty or not a valid object property.
     */
    fun <T : Any> validateValue(
        beanType: KClass<T>, propertyName: String, value: Any?, vararg groups: KClass<*>
    ): Set<ConstraintViolation<T>> = validateValue(
        getConstraintsForClass(beanType), propertyName, value, *groups
    )

    /**
     * Validates all constraint constraints on the `fieldName` field of the class described
     * by the given [DataClassDescriptor] if the `fieldName` field value were `value`.
     *
     * @param descriptor   the [DataClassDescriptor] of the described class.
     * @param propertyName field to validate.
     * @param value        field value to validate.
     * @param groups       the list of groups targeted for validation (defaults to Default).
     * @param T the type of the object to validate
     *
     * @return constraint violations or an empty set if none
     *
     * @throws IllegalArgumentException - if fieldName is null, empty or not a valid object property.
     */
    fun <T : Any> validateValue(
        descriptor: DataClassDescriptor<T>, propertyName: String, value: Any?, vararg groups: KClass<*>
    ): Set<ConstraintViolation<T>> {
        if (propertyName.isEmpty()) throw IllegalArgumentException("fieldName must not be empty.")

        return when (val propertyDescriptor = descriptor.members[propertyName]) {
            null -> throw IllegalArgumentException("$propertyName is not a field of ${descriptor.clazz}.")
            else -> {
                validate(maybeDescriptor = propertyDescriptor, context = ValidationContext(
                    propertyName, descriptor.clazz.java, null, null, PathImpl.createRootPath()
                ), value = value, groups = groups.map { it.java })
            }
        }
    }

    /**
     * Validates all constraint constraints on the `fieldName` field of the given object.
     *
     * ==Usage==
     * Validate the "id" field in data class `MyClass` (enabling the validation group "Checks"):
     *
     *      data class MyClass(@NotEmpty(groups = Checks::class) id)
     *      val i = MyClass("")
     *      validator.validateProperty(i, "id", listOf(Checks::class))
     *
     *
     * @param obj          object to validate.
     * @param propertyName property to validate (used in error reporting).
     * @param groups       the list of groups targeted for validation (defaults to Default).
     * @param T the type of the object to validate.
     *
     * @return constraint violations or an empty set if none.
     *
     * @throws IllegalArgumentException - if object is null, if fieldName is null, empty or not a valid object property.
     */
    fun <T : Any> validateProperty(
        obj: T, propertyName: String, vararg groups: KClass<*>
    ): Set<ConstraintViolation<T>> {
        if (propertyName.isEmpty()) throw IllegalArgumentException("fieldName must not be empty.")

        val dataClassDescriptor = getConstraintsForClass(obj::class)
        return when (dataClassDescriptor.members[propertyName]) {
            null -> throw IllegalArgumentException("$propertyName is not a field of ${dataClassDescriptor.clazz}.")
            else -> validate(maybeDescriptor = dataClassDescriptor, context = ValidationContext(
                propertyName, obj.javaClass, obj, obj, PathImpl.createRootPath()
            ), value = obj, groups = groups.map { it.java })
        }
    }

    /**
     * Returns an instance of the specified type allowing access to provider-specific APIs.
     *
     * If the Jakarta Bean Validation provider implementation does not support the specified class, ValidationException is thrown.
     *
     * @param clazz the class of the object to be returned
     * @param U the type of the object to be returned
     *
     * @return an instance of the specified class
     *
     * @throws ValidationException - if the provider does not support the call.
     */
    @Throws(ValidationException::class)
    @Suppress("UNCHECKED_CAST")
    fun <U> unwrap(clazz: Class<U>): U {
        // If this were an implementation that implemented the specification
        // interface, this is where users would be able to cast the validator from the
        // interface type into our specific implementation type in order to call methods
        // only available on the implementation. However, since the specification uses Java
        // collection types we do not directly implement and instead expose our feature-compatible
        // DataClassValidator directly. We try to remain true to the spirit of the specification
        // interface and thus implement unwrap but only to return this implementation.
        return if (clazz.isAssignableFrom(DataClassValidator::class.java)) {
            this as U
        } else if (clazz.isAssignableFrom(DataClassExecutableValidator::class.java)) {
            this as U
        } else {
            throw ValidationException("Type ${clazz.name} not supported for unwrapping.")
        }
    }

    // Executable Validator Methods ------------------------------------------------------------------------------------

    /** Returns the contract for validating parameters and return values of methods and constructors. */
    fun forExecutables(): DataClassExecutableValidator = this

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> validateMethods(
        obj: T, vararg groups: KClass<*>
    ): Set<ConstraintViolation<T>> {
        val dataClassDescriptor = getConstraintsForClass(obj::class)
        return validateMethods(rootClazz = obj::class.java as Class<T>,
            root = obj,
            methods = dataClassDescriptor.methods,
            obj = obj,
            groups = groups.map { it.java })
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> validateMethod(
        obj: T, method: KFunction<*>, vararg groups: KClass<*>
    ): Set<ConstraintViolation<T>> {
        val dataClassDescriptor = getConstraintsForClass(obj::class)
        return when (val methodDescriptor = dataClassDescriptor.methods.find { it.callable.name == method.name }) {
            null -> throw java.lang.IllegalArgumentException("${method.name} is not method of ${dataClassDescriptor.clazz}.")
            else -> {
                validateMethods(rootClazz = obj::class.java as Class<T>,
                    root = obj,
                    methods = listOf(methodDescriptor),
                    obj = obj,
                    groups = groups.map { it.java })
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> validateParameters(
        obj: T, method: KFunction<*>, parameterValues: List<Any?>, vararg groups: KClass<*>
    ): Set<ConstraintViolation<T>> {
        val dataClassDescriptor = getConstraintsForClass(obj::class)
        return when (val methodDescriptor = dataClassDescriptor.methods.find { it.callable.name == method.name }) {
            null -> throw java.lang.IllegalArgumentException("${method.name} is not method of ${dataClassDescriptor.clazz}.")
            else -> {
                validateExecutableParameters(
                    executableDescriptor = methodDescriptor as ExecutableDescriptor<T>,
                    root = obj,
                    leaf = obj,
                    parameterValues = parameterValues,
                    parameterNames = null,
                    groups = groups.map { it.java })
            }
        }
    }

    override fun <T : Any> validateReturnValue(
        obj: T, method: KFunction<*>, returnValue: Any, vararg groups: KClass<*>
    ): Set<ConstraintViolation<T>> = underlying.forExecutables().validateReturnValue(
        obj, method.javaMethod, returnValue, *groups.map { it.java }.toTypedArray()
    )

    override fun <T : Any> validateConstructorParameters(
        constructor: KFunction<T>, parameterValues: List<Any?>, vararg groups: KClass<*>
    ): Set<ConstraintViolation<T>> {
        val parameterCount = constructor.parameters.filter { it.kind == KParameter.Kind.VALUE }.size
        val parameterValuesLength = parameterValues.size
        if (parameterCount != parameterValuesLength) throw ValidationException(
            "Wrong number of parameters. Method or constructor $constructor expects $parameterCount parameters, but got $parameterValuesLength."
        )

        return validateExecutableParameters(
            executableDescriptor = descriptorFactory.describeConstructor(constructor),
            root = null,
            leaf = null,
            parameterValues = parameterValues,
            parameterNames = null,
            groups = groups.map { it.java })
    }

    override fun <R : Any> validateMethodParameters(
        method: KFunction<R>, parameterValues: List<Any?>, vararg groups: KClass<*>
    ): Set<ConstraintViolation<R>> {
        val parameterCount = method.parameters.filter { it.kind == KParameter.Kind.VALUE }.size
        val parameterValuesLength = parameterValues.size
        if (parameterCount != parameterValuesLength) throw ValidationException(
            "Wrong number of parameters. Method or constructor $method expects $parameterCount parameters, but got $parameterValuesLength."
        )
        return when (val descriptor = descriptorFactory.describeMethod(method)) {
            null -> emptySet()
            else ->
                validateExecutableParameters(
                    executableDescriptor = descriptor,
                    root = null,
                    leaf = null,
                    parameterValues = parameterValues,
                    parameterNames = null,
                    groups = groups.map { it.java })
        }
    }

    override fun <T : Any> validateConstructorReturnValue(
        constructor: KFunction<T>, createdObject: T, vararg groups: KClass<*>
    ): Set<ConstraintViolation<T>> = underlying.forExecutables().validateConstructorReturnValue(
        constructor.javaConstructor, createdObject, *groups.map { it.java }.toTypedArray()
    )

    override fun <T : Any> validateExecutableParameters(
        executable: ExecutableDescriptor<T>,
        parameterValues: List<Any?>,
        parameterNames: List<String>,
        vararg groups: KClass<*>
    ): Set<ConstraintViolation<T>> {
        val parameterCount = executable.callable.parameters.filter { it.kind == KParameter.Kind.VALUE }.size
        val parameterValuesLength = parameterValues.size
        if (parameterCount != parameterValuesLength) throw ValidationException(
            "Wrong number of parameters. Method or constructor $executable expects $parameterCount parameters, but got $parameterValuesLength."
        )
        return validateExecutableParameters(
            executableDescriptor = executable,
            root = null,
            leaf = null,
            parameterValues = parameterValues,
            parameterNames = parameterNames,
            groups = groups.map { it.java }
        )
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> validateMethods(
        methods: List<ExecutableDescriptor<Any>>, obj: T, vararg groups: KClass<*>
    ): Set<ConstraintViolation<T>> = validateMethods(rootClazz = obj::class.java as Class<T>,
        root = obj,
        methods = methods,
        obj = obj,
        groups = groups.map { it.java })

    /**
     * Evaluates all known [ConstraintValidator] instances supporting the constraints represented by the
     * given mapping of [Annotation] to constraint attributes placed on a field `fieldName` if
     * the `fieldName` field value were `value`. The `fieldName` is used in error reporting (as the
     * resulting property path for any resulting [ConstraintViolation]).
     *
     * E.g., the given mapping could be Map(jakarta.validation.constraint.Size -> Map("min" -> 0, "max" -> 1000))
     *
     * ConstraintViolation objects return `null` for ConstraintViolation.getRootBeanClass(),
     * ConstraintViolation.getRootBean() and ConstraintViolation.getLeafBean().
     *
     * @param constraints  the mapping of constraint [Annotation] to attributes to evaluate
     * @param fieldName    field to validate (used in error reporting).
     * @param value        field value to validate.
     * @param groups       the list of groups targeted for validation (defaults to Default).
     *
     * @return constraint violations or an empty set if none
     * @note the 'groups' attribute in the constraints mapping MUST be a java Class<*> and not a Kotlin KClass<*>
     *
     * @throws IllegalArgumentException - if fieldName is null, empty or not a valid object property.
     */
    fun validateFieldValue(
        constraints: Map<KClass<out Annotation>, Map<String, Any>>,
        fieldName: String,
        value: Any,
        vararg groups: KClass<*>
    ): Set<ConstraintViolation<Any>> {
        if (fieldName.isEmpty()) throw IllegalArgumentException("fieldName must not be empty.")
        val annotations = constraints.map { (constraintAnnotationType, attributes) ->
            AnnotationFactory.create(AnnotationDescriptor.Builder(constraintAnnotationType.java, attributes).build())
        }
        return validateFieldValue(fieldName, annotations, value, groups.map { it.java })
    }

    // Private functions -----------------------------------------------------------------------------------------------

    /** Validate the given fieldName with the given constraint constraints, value, and groups. */
    @Suppress("UNCHECKED_CAST")
    private fun validateFieldValue(
        fieldName: String, constraints: List<Annotation>, value: Any, groups: List<Class<*>>
    ): Set<ConstraintViolation<Any>> = if (constraints.isNotEmpty()) {
        val results = mutableListOf<ConstraintViolation<Any>>()
        var index = 0
        val length = constraints.size
        while (index < length) {
            val annotation = constraints[index]
            val path = PathImpl.createRootPath()
            path.addPropertyNode(fieldName)
            val clazz = value::class.java
            val context = ValidationContext(fieldName, clazz as Class<Any>, null, null, path)

            results.addAll(
                isValid(
                    context = context, constraint = annotation, clazz = clazz, value = value, groups = groups
                )
            )
            index += 1
        }
        results.toSet()
    } else emptySet()

    private fun <T : Any> validateExecutableParameters(
        executable: ExecutableDescriptor<T>,
        parameterValues: List<Any>,
        parameterNames: List<String>,
        vararg groups: Class<*>
    ): Set<ConstraintViolation<T>> {
        val parameterCount = executable.callable.parameters.size
        val parameterValuesLength = parameterValues.size
        if (parameterCount != parameterValuesLength) throw ValidationException(
            "Wrong number of parameters. Method or constructor $executable expects $parameterCount parameters, but got $parameterValuesLength."
        )

        return validateExecutableParameters(
            executableDescriptor = executable,
            root = null,
            leaf = null,
            parameterValues = parameterValues,
            parameterNames = parameterNames,
            groups = groups.toList()
        )
    }

    // BEGIN: Recursive validation methods -----------------------------------------------------------

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> validateField(
        context: ValidationContext<T>,
        propertyDescriptor: PropertyDescriptor?,
        fieldValue: Any?,
        groups: List<Class<*>>,
    ): Set<ConstraintViolation<T>> {
        val constraints: List<Annotation> = propertyDescriptor?.annotations ?: emptyList()
        val results = mutableListOf<ConstraintViolation<T>>()
        var index = 0
        val length = constraints.size
        while (index < length) {
            val constraint = constraints[index]
            results.addAll(
                isValid(
                    context,
                    constraint = constraint,
                    clazz = propertyDescriptor?.clazz?.java as Class<T>,
                    value = fieldValue,
                    groups = groups
                )
            )
            index += 1
        }

        // Cannot cascade a null or None value
        if (fieldValue != null) {
            if (propertyDescriptor?.isCascaded != null && propertyDescriptor.isCascaded) {
                results.addAll(
                    validateCascadedProperty(context, propertyDescriptor, fieldValue, groups)
                )
            }
        }

        return if (results.isEmpty()) emptySet()
        else results.toSet()
    }

    private fun <T : Any> isValid(
        context: ValidationContext<T>, constraint: Annotation, clazz: Class<T>, value: Any?, groups: List<Class<*>>
    ): Set<ConstraintViolation<T>> =
        isValid(context, constraint, clazz, value, null, null, groups)

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> isValid(
        context: ValidationContext<T>,
        constraint: Annotation,
        clazz: Class<out T>,
        value: Any?,
        constraintDescriptorOption: ConstraintDescriptorImpl<Annotation>?,
        validatorOption: ConstraintValidator<Annotation, Any>?,
        groups: List<Class<*>>
    ): Set<ConstraintViolation<T>> {
        val constraintDescriptor = when (constraintDescriptorOption) {
            null ->
                constraintDescriptorFactory.newConstraintDescriptor(
                    name = context.fieldName,
                    clazz = clazz,
                    declaringClazz = context.rootClazz ?: clazz,
                    annotation = constraint
                )

            else -> constraintDescriptorOption
        }

        return if (!ignorable(value, constraint) && groupsEnabled(constraintDescriptor, groups)) {
            val constraintValidator: ConstraintValidator<Annotation, Any>? = when (validatorOption) {
                null -> constraintValidatorManager.getInitializedValidator(
                    Types.refineAsJavaType(clazz, value),
                    constraintDescriptor,
                    validatorFactory.constraintValidatorFactory(),
                    validatorFactory.validatorFactoryScopedContext().constraintValidatorInitializationContext
                )?.let { it as ConstraintValidator<Annotation, Any> }

                else -> validatorOption // should already be initialized
            }

            when (constraintValidator) {
                null -> {
                    val configuration = context.path.toString().ifEmpty { clazz.simpleName }
                    throw UnexpectedTypeException(
                        "No validator could be found for constraint '${constraint.annotationClass}' " + "validating type '${clazz.name}'. " + "Check configuration for '$configuration'"
                    )
                }

                else -> {
                    // create validator context
                    val constraintValidatorContext: ConstraintValidatorContext =
                        constraintValidatorContextFactory.newConstraintValidatorContext(
                            context.path, constraintDescriptor
                        )
                    // compute if valid
                    if (constraintValidator.isValid(value, constraintValidatorContext)) emptySet()
                    else {
                        constraintViolationFactory.buildConstraintViolations(
                            rootClazz = context.rootClazz,
                            root = context.root,
                            leaf = context.leaf,
                            path = context.path,
                            invalidValue = value,
                            constraintDescriptor = constraintDescriptor,
                            constraintValidatorContext = constraintValidatorContext
                        )
                    }
                }
            }
        } else {
            emptySet()
        }
    }

    /** Validate cascaded field-level properties */
    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> validateCascadedProperty(
        context: ValidationContext<T>,
        propertyDescriptor: PropertyDescriptor?,
        clazzInstance: Any,
        groups: List<Class<*>>
    ): Set<ConstraintViolation<T>> {
        return when (propertyDescriptor?.cascadedType) {
            null -> emptySet()
            else -> {
                // Update the found value with the current landscape, for example, the retrieved data class
                // descriptor may be type Foo, but this property is a List<Foo> and we want to carry
                // that property typing through.
                val dataClassDescriptor =
                    descriptorFactory
                        .describe(clazz = propertyDescriptor.cascadedType.jvmErasure as KClass<T>)
                        .copy(clazz = propertyDescriptor.clazz as KClass<T>)

                val dataClassPath = PathImpl.createCopy(context.path)
                when {
                    Collection::class.java.isAssignableFrom(propertyDescriptor.clazz.java) -> {
                        val results = mutableListOf<ConstraintViolation<T>>()
                        val collectionValue: Iterable<*> = clazzInstance as Iterable<*>
                        val collectionValueIterator = collectionValue.iterator()
                        var index = 0
                        while (collectionValueIterator.hasNext()) {
                            val instanceValue = collectionValueIterator.next()
                            // apply the index to the parent path, then use this to recompute paths of members and methods
                            val indexedPath = PathImpl.createCopyWithoutLeafNode(dataClassPath)
                            indexedPath.addPropertyNode(
                                "${dataClassPath.leafNode.asString()}[${index}]"
                            )

                            val violations = validate(
                                maybeDescriptor = dataClassDescriptor,
                                context = context.copy(path = indexedPath),
                                value = instanceValue,
                                groups = groups
                            )
                            if (violations.isNotEmpty()) results.addAll(violations)
                            index += 1
                        }
                        if (results.isNotEmpty()) results.toSet()
                        else emptySet()
                    }

                    else -> validate(
                        maybeDescriptor = dataClassDescriptor,
                        context = context.copy(path = dataClassPath),
                        value = clazzInstance,
                        groups = groups
                    )
                }
            }
        }
    }

    /** Validate @MethodValidation annotated methods */
    private fun <T : Any> validateMethod(
        context: ValidationContext<T>,
        methodDescriptor: ExecutableDescriptor<Any>,
        clazzInstance: Any,
        groups: List<Class<*>>
    ): Set<ConstraintViolation<T>> = when (val annotation = methodDescriptor.annotations.find<MethodValidation>()) {
        null -> emptySet()
        else -> {
            // if we're unable to invoke the method, we want this propagate
            val constraintDescriptor = MethodValidationConstraintDescriptor(annotation as MethodValidation)
            if (groupsEnabled(constraintDescriptor, groups)) {
                try {
                    val result: MethodValidationResult =
                        methodDescriptor.callable.call(clazzInstance) as MethodValidationResult
                    val pathWithMethodName = PathImpl.createCopy(context.path)
                    pathWithMethodName.addPropertyNode(methodDescriptor.callable.name)
                    parseMethodValidationFailures(
                        rootClazz = context.rootClazz,
                        root = context.root,
                        leaf = clazzInstance,
                        path = pathWithMethodName,
                        annotation = annotation,
                        result = result,
                        value = clazzInstance,
                        constraintDescriptor
                    )
                } catch (e: InvocationTargetException) {
                    if (e.cause != null) throw e.cause!! else throw e
                }
            } else emptySet()
        }
    }

    /** Validate class-level constraints */
    private fun <T : Any> validateClazz(
        context: ValidationContext<T>,
        dataClassDescriptor: DataClassDescriptor<T>,
        clazzInstance: Any?,
        groups: List<Class<*>>
    ): Set<ConstraintViolation<T>> {
        val constraints = dataClassDescriptor.annotations
        return if (constraints.isNotEmpty()) {
            val results = mutableListOf<ConstraintViolation<T>>()
            var index = 0
            val length = constraints.size
            while (index < length) {
                val annotation = constraints[index]
                results.addAll(
                    isValid(
                        context = context,
                        constraint = annotation,
                        clazz = dataClassDescriptor.clazz.java,
                        value = clazzInstance,
                        groups = groups
                    )
                )
                index += 1
            }
            results.toSet()
        } else emptySet()
    }

    /** Recursively validate full data class */
    @Suppress("UNCHECKED_CAST")
    private tailrec fun <T : Any> validate(
        maybeDescriptor: Descriptor?, context: ValidationContext<T>, value: Any?, groups: List<Class<*>>
    ): Set<ConstraintViolation<T>> = when {
        maybeDescriptor != null -> {
            when (maybeDescriptor) {
                is PropertyDescriptor -> {
                    val propertyPath = PathImpl.createCopy(context.path)
                    context.fieldName?.let { propertyPath.addPropertyNode(it) }
                    // validateField will recurse back through validate for cascaded properties
                    validateField(context.copy(path = propertyPath), maybeDescriptor, value, groups)
                }

                is DataClassDescriptor<*> -> {
                    val dataClassDescriptor: DataClassDescriptor<T> = maybeDescriptor as DataClassDescriptor<T>
                    val memberViolationResults = mutableListOf<ConstraintViolation<T>>()
                    if (dataClassDescriptor.members.isNotEmpty()) {
                        val keys: Iterable<String> = dataClassDescriptor.members.keys
                        val keyIterator = keys.iterator()
                        while (keyIterator.hasNext()) {
                            val name = keyIterator.next()
                            val propertyDescriptor = dataClassDescriptor.members[name]
                            val propertyPath = PathImpl.createCopy(context.path)
                            propertyPath.addPropertyNode(name)
                            // validateField will recurse back through validate here for cascaded properties
                            val fieldResults = validateField(
                                context.copy(fieldName = name, path = propertyPath),
                                propertyDescriptor,
                                findFieldValue(value, dataClassDescriptor.type as Class<*>, name),
                                groups
                            )
                            if (fieldResults.isNotEmpty()) memberViolationResults.addAll(fieldResults)
                        }
                    }
                    val memberViolations = memberViolationResults.toSet()

                    val methodViolationsResults = mutableListOf<ConstraintViolation<T>>()
                    if (dataClassDescriptor.methods.isNotEmpty()) {
                        var index = 0
                        val length = dataClassDescriptor.methods.size
                        while (index < length) {
                            val methodResults = when (value) {
                                null -> emptySet()
                                else -> validateMethod(
                                    context.copy(fieldName = null), dataClassDescriptor.methods[index], value, groups
                                )
                            }

                            if (methodResults.isNotEmpty()) methodViolationsResults.addAll(methodResults)
                            index += 1
                        }
                    }
                    val methodViolations = methodViolationsResults.toSet()

                    val clazzViolations = validateClazz(context, dataClassDescriptor, value, groups)

                    // put them all together
                    memberViolations + methodViolations + clazzViolations
                }

                else -> throw IllegalArgumentException("")
            }
        }

        else -> {
            val clazz: Class<T> = value?.javaClass as Class<T>
            if (!value::class.isData) throw ValidationException("$clazz is not a valid data class.")
            val dataClassDescriptor: DataClassDescriptor<T> = descriptorFactory.describe(clazz.kotlin)
            validate(
                dataClassDescriptor,
                ValidationContext(
                    fieldName = null,
                    rootClazz = clazz,
                    root = value as T,
                    leaf = value,
                    path = PathImpl.createRootPath()
                ),
                value,
                groups
            )
        }
    }

    // END: Recursive validation methods -------------------------------------------------------------

    /** Validate Executable field-level parameters (including cross-field parameters) */
    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> validateExecutableParameters(
        executableDescriptor: ExecutableDescriptor<T>,
        root: T?,
        leaf: Any?,
        parameterValues: List<Any?>,
        parameterNames: List<String>?,
        groups: List<Class<*>>
    ): Set<ConstraintViolation<T>> {
        if (executableDescriptor.members.size != parameterValues.size) throw IllegalArgumentException(
            "Invalid number of arguments for method ${executableDescriptor.callable.name}."
        )
        val results = mutableListOf<ConstraintViolation<T>>()
        val parameters: List<KParameter> =
            executableDescriptor.callable.parameters.filter { it.kind == KParameter.Kind.VALUE }
        val parameterNamesList: List<String> =
            parameterNames ?: getExecutableParameterNames(executableDescriptor.callable as KFunction<*>)

        // executable parameter constraints
        var index = 0
        val parametersLength = parameterNamesList.size
        while (index < parametersLength) {
            val parameter = parameters[index]
            val parameterValue = parameterValues[index]
            // only for property path use to affect error reporting
            val parameterName = parameterNamesList[index]

            val parameterPath =
                PathImpl.createPathForExecutable(getExecutableMetaData(executableDescriptor.callable as KFunction<*>))
            parameterPath.addParameterNode(parameterName, index)

            val propertyDescriptor = executableDescriptor.members[parameter.name]
            val validateFieldContext = ValidationContext(
                parameterName, executableDescriptor.declaringClass as Class<T>, root, leaf, parameterPath
            )
            val parameterViolations = validateField(
                validateFieldContext, propertyDescriptor, parameterValue, groups
            )
            if (parameterViolations.isNotEmpty()) results.addAll(parameterViolations)

            index += 1
        }
        results.toSet()

        // executable cross-parameter constraints
        val crossParameterPath =
            PathImpl.createPathForExecutable(getExecutableMetaData(executableDescriptor.callable as KFunction<*>))
        crossParameterPath.addCrossParameterNode()
        val constraints = executableDescriptor.annotations
        index = 0
        val constraintsLength = constraints.size
        while (index < constraintsLength) {
            val constraint = constraints[index]
            val validators =
                findConstraintValidators(constraint.annotationClass).filter(parametersValidationTargetFilter)

            val validatorsIterator = validators.iterator()
            while (validatorsIterator.hasNext()) {
                val validator = validatorsIterator.next()
                val constraintDescriptor = constraintDescriptorFactory.newConstraintDescriptor(
                    name = executableDescriptor.callable.name,
                    clazz = parameterValues::class.java,
                    declaringClazz = executableDescriptor.declaringClass,
                    annotation = constraint,
                    constrainedElementKind = ConstrainedElement.ConstrainedElementKind.METHOD
                )
                if (groupsEnabled(constraintDescriptor, groups)) {
                    // initialize validator
                    (validator as ConstraintValidator<Annotation, *>).initialize(constraint)
                    val validateCrossParameterContext = ValidationContext(
                        null, executableDescriptor.declaringClass as Class<T>, root, leaf, crossParameterPath
                    )
                    results.addAll(
                        isValid(
                            context = validateCrossParameterContext,
                            constraint = constraint,
                            clazz = parameterValues::class.java as Class<T>,
                            value = parameterValues,
                            constraintDescriptorOption = constraintDescriptor,
                            validatorOption = validator as ConstraintValidator<Annotation, Any>,
                            groups = groups
                        )
                    )
                }
            }
            index += 1
        }
        return results.toSet()
    }

    /** Validate method-level constraints, i.e., @MethodValidation annotated methods */
    private fun <T : Any> validateMethods(
        rootClazz: Class<T>, root: T, methods: List<ExecutableDescriptor<Any>>, obj: T, groups: List<Class<*>>
    ): Set<ConstraintViolation<T>> {
        val results = mutableListOf<ConstraintViolation<T>>()
        if (methods.isNotEmpty()) {
            var index = 0
            val length = methods.size
            while (index < length) {
                val methodDescriptor = methods[index]
                val validateMethodContext = ValidationContext(null, rootClazz, root, obj, PathImpl.createRootPath())
                results.addAll(
                    validateMethod(validateMethodContext, methodDescriptor, obj, groups)
                )
                index += 1
            }
        }
        return results.toSet()
    }

    // https://beanvalidation.org/2.0/spec/#constraintsdefinitionimplementation-validationimplementation
    // From the documentation:
    //
    // While not mandatory, it is considered a good practice to split the core constraint
    // validation from the "not null" constraint validation (for example, an @Email constraint
    // should return "true" on a `null` object, i.e. it will not also assert the @NotNull validation).
    // A "null" can have multiple meanings but is commonly used to express that a value does
    // not make sense, is not available or is simply unknown. Those constraints on the
    // value are orthogonal in most cases to other constraints. For example a String,
    // if present, must be an email but can be null. Separating both concerns is a good
    // practice.
    //
    // Thus, we "ignore" any value that is null and not annotated with @NotNull.
    private fun ignorable(value: Any?, annotation: Annotation): Boolean {
        return if (value == null) {
            annotation.notEq<jakarta.validation.constraints.NotNull>() // ignorable null
        } else if (Types.isFunction(value::class.java)) {
            true // cannot constrain function arguments
        } else false
    }

    private fun groupsEnabled(
        constraintDescriptor: ConstraintDescriptor<*>, groups: List<Class<*>>
    ): Boolean {
        val groupsFromAnnotation: Set<Class<*>> = constraintDescriptor.groups
        return if (groups.isEmpty() && groupsFromAnnotation.isEmpty()) true
        else if (groups.isEmpty() && groupsFromAnnotation.contains(Default::class.java)) true
        else if (groups.contains(Default::class.java) && groupsFromAnnotation.isEmpty()) true
        else groups.any { groupsFromAnnotation.contains(it) }
    }

    private val getExecutableParameterNames = ::getExecutableParameterNamesFn.memoize()

    /** @note this method is memoized as it should only ever need to be calculated once for a given [KFunction] */
    private fun getExecutableParameterNamesFn(kFunction: KFunction<*>): List<String> =
        kFunction.parameters.filter { it.kind == KParameter.Kind.VALUE }.map { it.name!! }

    private val parametersValidationTargetFilter = ::parametersValidationTargetFilterFn.memoize()

    /** @note this method is memoized as it should only ever need to be calculated once for a given [[ConstraintValidator]] */
    private fun parametersValidationTargetFilterFn(constraintValidator: ConstraintValidator<*, *>): Boolean {
        return when (val annotation = constraintValidator::class.annotations.find(SupportedValidationTarget::class)) {
            null -> false
            else -> (annotation as SupportedValidationTarget).value.contains(ValidationTarget.PARAMETERS)
        }
    }

    private val getExecutableMetaData = ::getExecutableMetaDataFn.memoize()

    /** @note this method is memoized as it should only ever need to be calculated once for a given [KFunction] */
    private fun getExecutableMetaDataFn(kFunction: KFunction<*>): ExecutableMetaData {
        val callable = when {
            kFunction.javaConstructor != null ->
                JavaBeanFactory.newJavaBeanConstructor(kFunction.javaConstructor)

            kFunction.javaMethod != null ->
                JavaBeanFactory.newJavaBeanMethod(kFunction.javaMethod)

            else -> throw IllegalArgumentException("")
        }

        val builder = ExecutableMetaData.Builder(
            callable.declaringClass,
            ConstrainedExecutable(
                ConfigurationSource.ANNOTATION,
                callable,
                callable.parameters.map { p ->
                    ConstrainedParameter(
                        ConfigurationSource.ANNOTATION, callable, p.type, p.index
                    )
                },
                Collections.emptySet(),
                Collections.emptySet(),
                Collections.emptySet(),
                CascadingMetaDataBuilder.nonCascading()
            ),
            validatorFactory.getConstraintCreationContext(),
            validatorFactory.getExecutableHelper(),
            validatorFactory.getExecutableParameterNameProvider(),
            validatorFactory.getMethodValidationConfiguration()
        )

        return builder.build()
    }

    private fun <T : Any> findFieldValue(
        obj: Any?, clazz: Class<T>, name: String
    ): Any? = obj.mapNotNull { instance ->
        try {
            val field = clazz.getDeclaredField(name)
            field.setAccessible(true)
            field.get(instance)
        } catch (e: Exception) {
            if (NonFatal.isNonFatal(e)) throw ValidationException(e)
            else throw e
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> parseMethodValidationFailures(
        rootClazz: Class<T>?,
        root: T?,
        leaf: Any?,
        path: PathImpl,
        annotation: MethodValidation,
        result: MethodValidationResult,
        value: Any,
        constraintDescriptor: ConstraintDescriptor<*>
    ): Set<ConstraintViolation<T>> {
        fun methodValidationConstraintViolation(
            validationPath: PathImpl, message: String?, payload: Payload?
        ): ConstraintViolation<T> = constraintViolationFactory.newConstraintViolation(
            messageTemplate = annotation.message,
            interpolatedMessage = message,
            path = validationPath,
            invalidValue = value as T,
            rootClazz = rootClazz,
            root = root,
            leaf = leaf,
            constraintDescriptor = constraintDescriptor,
            payload = payload
        )

        return when (result) {
            is MethodValidationResult.Invalid -> {
                val results = mutableSetOf<ConstraintViolation<T>>()
                val annotationFields: List<String> = annotation.fields.filter { it.isNotEmpty() }
                if (annotationFields.isNotEmpty()) {
                    var index = 0
                    val length = annotationFields.size
                    while (index < length) {
                        val fieldName = annotationFields[index]
                        val parameterPath = PathImpl.createCopy(path)
                        parameterPath.addParameterNode(fieldName, index)
                        results.add(
                            methodValidationConstraintViolation(
                                parameterPath, result.message, result.payload
                            )
                        )
                        index += 1
                    }
                    if (results.isNotEmpty()) results.toSet()
                    else emptySet()
                } else {
                    setOf(methodValidationConstraintViolation(path, result.message, result.payload))
                }
            }

            else -> emptySet()
        }
    }
}
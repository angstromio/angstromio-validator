package angstromio.validation

import angstromio.util.control.NonFatal
import angstromio.util.extensions.Annotations.eq
import angstromio.util.extensions.Annotations.notEq
import angstromio.util.extensions.Anys.isInstanceOf
import angstromio.util.extensions.Nulls.mapNotNull
import angstromio.util.extensions.Nulls.whenNotNull
import angstromio.validation.cfg.ConstraintMapping
import angstromio.validation.constraints.PostConstructValidation
import angstromio.validation.engine.PostConstructValidationResult
import angstromio.validation.internal.ConstraintValidatorFactoryHelper
import angstromio.validation.internal.Types
import angstromio.validation.internal.ValidationContext
import angstromio.validation.internal.constraintvalidation.ConstraintValidatorContextFactory
import angstromio.validation.internal.engine.ClassHelper
import angstromio.validation.internal.engine.ConstraintViolationHelper
import angstromio.validation.internal.metadata.descriptor.ConstraintDescriptorFactory
import arrow.core.memoize
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.ConstraintViolation
import jakarta.validation.MessageInterpolator
import jakarta.validation.UnexpectedTypeException
import jakarta.validation.Validation
import jakarta.validation.ValidationException
import jakarta.validation.Validator
import jakarta.validation.executable.ExecutableValidator
import jakarta.validation.groups.Default
import jakarta.validation.metadata.BeanDescriptor
import jakarta.validation.metadata.ConstraintDescriptor
import jakarta.validation.metadata.ElementDescriptor
import jakarta.validation.metadata.ExecutableDescriptor
import jakarta.validation.metadata.MethodDescriptor
import jakarta.validation.metadata.MethodType
import jakarta.validation.metadata.PropertyDescriptor
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
import org.hibernate.validator.internal.metadata.raw.ConstrainedExecutable
import org.hibernate.validator.internal.metadata.raw.ConstrainedParameter
import org.hibernate.validator.internal.properties.javabean.JavaBeanFactory
import org.hibernate.validator.internal.util.ExecutableHelper
import org.hibernate.validator.internal.util.annotation.AnnotationDescriptor
import org.hibernate.validator.internal.util.annotation.AnnotationFactory
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.*
import kotlin.reflect.KCallable
import kotlin.reflect.KFunction

@Suppress("UNCHECKED_CAST")
class DataClassValidator(
    cacheSize: Long,
    private val validatorFactory: ValidatorFactoryInspector,
) : Validator, ExecutableValidator {

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
                    .constraintDefinition(constraintMapping.annotationClazz as Class<Annotation>)
                    .includeExistingValidators(constraintMapping.includeExistingValidators)
                    .validatedBy(constraintMapping.constraintValidator as Class<ConstraintValidator<Annotation, *>>)
                configuration.addMapping(hibernateConstraintMapping)
            }

            return DataClassValidator(
                cacheSize = this.descriptorCacheSize,
                ValidatorFactoryInspector(configuration.buildValidatorFactory() as ValidatorFactoryImpl)
            )
        }
    }

    private val constraintViolationHelper: ConstraintViolationHelper = ConstraintViolationHelper(validatorFactory)

    private val constraintDescriptorFactory: ConstraintDescriptorFactory = ConstraintDescriptorFactory(validatorFactory)

    internal val descriptorFactory: DescriptorFactory =
        DescriptorFactory(cacheSize, validatorFactory, constraintDescriptorFactory)

    private val constraintValidatorContextFactory: ConstraintValidatorContextFactory =
        ConstraintValidatorContextFactory(validatorFactory)

    private val constraintValidatorManager: ConstraintValidatorManager =
        validatorFactory.constraintCreationContext.constraintValidatorManager

    fun close() {
        descriptorFactory.close()
        validatorFactory.close()
    }

    // BEGIN: jakarta.validationValidator methods ----------------------------------------------------------------------

    /** @inheritDoc */
    override fun getConstraintsForClass(clazz: Class<*>): BeanDescriptor = descriptorFactory.describe(clazz)

    /** @inheritDoc */
    override fun <T : Any> validate(
        obj: T,
        vararg groups: Class<*>
    ): Set<ConstraintViolation<T>> =
        validateDescriptor(
            descriptor = null,
            context = ValidationContext(null, null, null, null, PathImpl.createRootPath()),
            value = obj,
            groups = groups.toList()
        )

    /** @inheritDoc */
    override fun <T : Any> validateValue(
        beanType: Class<T>,
        propertyName: String,
        value: Any?,
        vararg groups: Class<*>
    ): Set<ConstraintViolation<T>> {
        if (propertyName.isEmpty()) throw IllegalArgumentException("Invalid property path. Property path cannot be null or empty.")
        return validateValue(
            descriptor = getConstraintsForClass(beanType),
            propertyName = propertyName,
            value = value,
            includeClassNameInPath = false,
            groups = groups
        )
    }

    /** @inheritDoc */
    override fun <T : Any> validateProperty(
        obj: T,
        propertyName: String,
        vararg groups: Class<*>
    ): Set<ConstraintViolation<T>> {
        if (propertyName.isEmpty()) throw IllegalArgumentException("Invalid property path. Property path cannot be null or empty.")

        val descriptor = getConstraintsForClass(obj::class.java)
        return when (descriptor.constrainedProperties.find { it.propertyName == propertyName }) {
            null -> throw IllegalArgumentException("$propertyName is not a field of ${descriptor.elementClass}.")
            else -> validateDescriptor(
                descriptor = descriptor,
                context = ValidationContext(
                    propertyName, obj.javaClass, obj, obj, PathImpl.createRootPath()
                ),
                value = obj,
                groups = groups.toList()
            )
        }
    }

    /** @inheritDoc */
    @Throws(ValidationException::class)
    override fun <U> unwrap(clazz: Class<U>): U {
        return if (clazz.isAssignableFrom(DataClassValidator::class.java)) {
            this as U
        } else {
            throw ValidationException("Type ${clazz.name} not supported for unwrapping.")
        }
    }

    /** @inheritDoc */
    override fun forExecutables(): ExecutableValidator = this

    // END: jakarta.validation.Validator methods -----------------------------------------------------------------------

    // BEGIN jakarta.validation.executable.ExecutableValidator Methods -------------------------------------------------

    /** @inheritDoc */
    override fun <T : Any> validateParameters(
        obj: T,
        method: Method,
        parameterValues: Array<Any?>,
        vararg groups: Class<*>
    ): Set<ConstraintViolation<T>> {
        val descriptor = getConstraintsForClass(obj::class.java)
        return when (val methodDescriptor = descriptor.getConstraintsForMethod(method.name, *method.parameterTypes)) {
            null -> // no constrained parameters in the method
                emptySet()
            else ->
                validateParameters(
                    obj = obj,
                    executable = method,
                    executableDescriptor = methodDescriptor,
                    parameterValues = parameterValues,
                    groups = groups.toList()
                )
        }
    }

    /** @inheritDoc */
    override fun <T : Any> validateReturnValue(
        obj: T,
        method: Method,
        returnValue: Any?,
        vararg groups: Class<*>
    ): Set<ConstraintViolation<T>> {
        val descriptor = getConstraintsForClass(obj::class.java)
        return when (val methodDescriptor = descriptor.getConstraintsForMethod(method.name, *method.parameterTypes)) {
            null -> // no constrained parameters in the method
                emptySet()
            else -> {
                val methodPath = PathImpl.createPathForExecutable(getExecutableMetaData(method))
                methodPath.addReturnValueNode()

                validateReturnValue(
                    context = ValidationContext(
                        fieldName = methodDescriptor.name,
                        rootClazz = obj::class.java as Class<T>,
                        root = obj,
                        leaf = obj,
                        path = methodPath
                    ),
                    executableDescriptor = methodDescriptor,
                    value = returnValue,
                    groups = groups.toList()
                )
            }
        }
    }

    /** @inheritDoc */
    override fun <T : Any> validateConstructorParameters(
        constructor: Constructor<out T>,
        parameterValues: Array<Any?>,
        vararg groups: Class<*>
    ): Set<ConstraintViolation<T>> {
        return when (val constructorDescriptor = descriptorFactory.describeConstructor(constructor)) {
            null -> // no constrained parameters in the constructor
                emptySet()
            else ->
             validateParameters(
                obj = null,
                executable = constructor,
                executableDescriptor = constructorDescriptor,
                parameterValues = parameterValues,
                groups = groups.toList()
            )
        }
    }

    /** @inheritDoc */
    override fun <T : Any> validateConstructorReturnValue(
        constructor: Constructor<out T>,
        createdObject: T,
        vararg groups: Class<*>
    ): Set<ConstraintViolation<T>> {
        // Note: we could do descriptorFactory#describeConstructor here, but we want to ensure that the passed constructor
        // is an actual constrained constructor of the given 'createdObject' instance.
        val descriptor = getConstraintsForClass(createdObject::class.java)
        return when (val constructorDescriptor = descriptor.getConstraintsForConstructor(*constructor.parameterTypes)) {
            null -> // no constrained parameters in the constructor
                emptySet()
            else -> {
                val constructorPath = PathImpl.createPathForExecutable(getExecutableMetaData(constructor))
                constructorPath.addReturnValueNode()

                validateReturnValue(
                    context = ValidationContext(
                        fieldName = constructorDescriptor.name,
                        rootClazz = createdObject::class.java as Class<T>,
                        root = createdObject,
                        leaf = createdObject,
                        path = constructorPath
                    ),
                    executableDescriptor = constructorDescriptor,
                    value = createdObject,
                    groups = groups.toList()
                )
            }
        }
    }

    // END: jakarta.validation.executable.ExecutableValidator methods --------------------------------------------------

    // BEGIN: angstromio.validation.DataClassValidator methods ---------------------------------------------------------

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
    fun describeExecutable(
        executable: Executable,
        mixinClazz: Class<*>?
    ): ExecutableDescriptor? = descriptorFactory.describe(executable, mixinClazz)

    /**
     * Returns a list of [ExecutableDescriptor] instances describing the `@PostConstructValidation`-annotated
     * or otherwise constrained methods of the given data class type.
     *
     * @param clazz class or interface type evaluated
     *
     * @return a list of [ExecutableDescriptor] for `@PostConstructValidation`-annotated or otherwise
     *         constrained methods of the class
     *
     * @note the returned [ExecutableDescriptor] instances are NOT cached. It is up to the caller of this
     *       method to optimize any calls to this method.
     */
    fun describeMethods(clazz: Class<*>): List<MethodDescriptor> = descriptorFactory.describeMethods(clazz)

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
    private fun findConstraintValidatorsFn(annotationClazz: (Class<out Annotation>)): Set<ConstraintValidator<*, *>> {
        return ConstraintValidatorFactoryHelper.findConstraintValidators(
            validatorFactory = validatorFactory,
            annotationClazz = annotationClazz
        )
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
    fun isConstraintAnnotation(annotation: Annotation): Boolean =
        isConstraintAnnotation(annotation.annotationClass.java)

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
    private fun isConstraintAnnotationClazz(clazz: Class<out Annotation>): Boolean =
        validatorFactory.constraintHelper.isConstraintAnnotation(clazz)

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
        obj: Any,
        vararg groups: Class<*>
    ) {
        val invalidResult = validate(obj, *groups)
        if (invalidResult.isNotEmpty()) throw constraintViolationHelper.newConstraintViolationException(invalidResult)
    }

    /**
     * Validates all constraint constraints on the `fieldName` field of the class described
     * by the given [BeanDescriptor] if the `fieldName` field value were `value`.
     *
     * @param descriptor   the [BeanDescriptor] of the described class.
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
        descriptor: BeanDescriptor,
        propertyName: String,
        value: Any?,
        includeClassNameInPath: Boolean,
        vararg groups: Class<*>
    ): Set<ConstraintViolation<T>> {
        if (propertyName.isEmpty()) throw IllegalArgumentException("Invalid property path. Property path cannot be null or empty.")

        return when (val propertyDescriptor =
            descriptor.constrainedProperties.find { it.propertyName == propertyName }) {
            null -> emptySet()
            else -> {
                val path = PathImpl.createRootPath()
                if (includeClassNameInPath) {
                    path.addPropertyNode(descriptor.elementClass.simpleName)
                }
                validateDescriptor(
                    descriptor = propertyDescriptor,
                    context = ValidationContext(
                        fieldName = propertyName,
                        rootClazz = descriptor.elementClass as Class<T>,
                        root = null,
                        leaf = null,
                        path = path
                    ),
                    value = value,
                    groups = groups.toList()
                )
            }
        }

    }

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
        constraints: Map<Class<out Annotation>, Map<String, Any>>,
        fieldName: String,
        value: Any,
        vararg groups: Class<*>
    ): Set<ConstraintViolation<Any>> {
        if (fieldName.isEmpty()) throw IllegalArgumentException("fieldName must not be empty.")
        val annotations = constraints.map { (constraintAnnotationType, attributes) ->
            AnnotationFactory.create(AnnotationDescriptor.Builder(constraintAnnotationType, attributes).build())
        }
        return validateFieldValue(fieldName, annotations, value, groups.toList())
    }

    /**
     * Given a constructed object instance, invoke any @PostConstructValidation annotated methods and
     * return the set of failing ConstraintViolations.
     *
     * @return set of any failed validations as ConstraintViolations.
     */
    fun <T : Any> validatePostConstructValidationMethods(
        obj: T,
        vararg groups: Class<*>
    ): Set<ConstraintViolation<T>> {
        val methods = obj::class.java.declaredMethods
        val results = mutableSetOf<ConstraintViolation<T>>()
        methods.forEach { method ->
            results.addAll(
                validatePostConstructValidationMethod(
                    obj = obj,
                    method = method,
                    groups = groups
                )
            )
        }
        return results.toSet()
    }


    /**
     * If the given method is annotated with an @PostConstructValidation annotation, execute the validation given the
     * object instance. If the given method does not represent an @PostConstructValidation annotated method of the
     * given object instance, no validation will be performed and an empty set of violations will be returned. Otherwise,
     * any failing validations will be returned as the set of ConstraintViolations.
     *
     * @return the set of failing validations.
     */
    fun <T : Any> validatePostConstructValidationMethod(
        obj: T,
        method: Method,
        vararg groups: Class<*>
    ): Set<ConstraintViolation<T>> {
        val results = mutableSetOf<ConstraintViolation<T>>()
        // Note: we could do descriptorFactory#describeMethod here, but we want to ensure the PostConstructValidation
        // is an actual method on the given obj instance.
        val descriptor = getConstraintsForClass(obj::class.java)
        when (val methodDescriptor = descriptor.getConstraintsForMethod(method.name, *method.parameterTypes)) {
            null -> Unit
            else -> {
                val returnValueDescriptor =  methodDescriptor.returnValueDescriptor
                // if the method has an @PostConstructValidation annotation, run the validation
                val constraintDescriptor: ConstraintDescriptorImpl<PostConstructValidation>? =
                   if (returnValueDescriptor != null) {
                           returnValueDescriptor
                               .constraintDescriptors
                                .find { it.annotation.eq<PostConstructValidation>() } as? ConstraintDescriptorImpl<PostConstructValidation>
                       } else null

                constraintDescriptor.whenNotNull { notNullConstraintDescriptor ->
                    val methodPath = PathImpl.createPathForExecutable(getExecutableMetaData(method))
                    results.addAll(
                        executePostConstructValidations(
                            context = ValidationContext(
                                fieldName = method.name,
                                rootClazz = obj::class.java,
                                root = obj,
                                leaf = obj,
                                path = methodPath
                            ),
                            method = method,
                            constraintDescriptor = notNullConstraintDescriptor,
                            clazzInstance = obj,
                            groups = groups.toList()
                        )
                    )
                }
            }
        }

        return results.toSet()
    }


    // BEGIN: Private functions ----------------------------------------------------------------------------------------

    /* PRIVATE */

    // BEGIN: Recursive validation methods -----------------------------------------------------------------------------

    private fun <T : Any> validateField(
        context: ValidationContext<T>,
        propertyDescriptor: PropertyDescriptor?,
        fieldValue: Any?,
        groups: List<Class<*>>,
    ): Set<ConstraintViolation<T>> {
        return if (propertyDescriptor != null) {
            val results = mutableListOf<ConstraintViolation<T>>()

            val constraintDescriptors = propertyDescriptor.constraintDescriptors
            val iterator = constraintDescriptors.iterator()
            while (iterator.hasNext()) {
                val constraintDescriptor = iterator.next()

                results.addAll(
                    isValid(
                        context = context,
                        constraintDescriptor = constraintDescriptor as ConstraintDescriptorImpl<Annotation>,
                        clazz = propertyDescriptor.elementClass as Class<T>,
                        value = fieldValue,
                        groups = groups
                    )
                )
            }

            // Cannot cascade a null value
            if (fieldValue != null) {
                if (propertyDescriptor.isCascaded) {
                    results.addAll(
                        if (propertyDescriptor.constrainedContainerElementTypes.isNotEmpty()) {
                            // need to cascade the constrained container element type, multi type containers are not supported
                            // thus we only read the first constrained container element type
                            validateCascadedProperty(
                                context = context,
                                isCollection = true,
                                clazz = propertyDescriptor.constrainedContainerElementTypes.first().elementClass,
                                clazzInstance = fieldValue,
                                groups = groups
                            )
                        } else {
                            validateCascadedProperty(
                                context = context,
                                isCollection = false,
                                clazz = propertyDescriptor.elementClass,
                                clazzInstance = fieldValue,
                                groups = groups
                            )
                        }
                    )
                }
            }

            results.toSet()
        } else emptySet()
    }

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

    private fun <T : Any> isValid(
        context: ValidationContext<T>,
        constraint: Annotation,
        clazz: Class<out T>,
        value: Any?,
        groups: List<Class<*>>
    ): Set<ConstraintViolation<T>> =
        isValid(
            context = context,
            constraintDescriptor = constraintDescriptorFactory
                .newConstraintDescriptor(
                    name = context.fieldName,
                    clazz = clazz,
                    declaringClazz = context.rootClazz ?: clazz,
                    annotation = constraint
                ),
            clazz = clazz,
            value = value,
            groups = groups
        )

    private fun <T : Any> isValid(
        context: ValidationContext<T>,
        constraintDescriptor: ConstraintDescriptorImpl<Annotation>,
        clazz: Class<*>,
        value: Any?,
        groups: List<Class<*>>
    ): Set<ConstraintViolation<T>> {
        return if (isValidationEnabled(value, constraintDescriptor, groups)) {
            val constraintValidator: ConstraintValidator<Annotation, Any>? =
                ConstraintValidatorFactoryHelper.findInitializedConstraintValidator(
                    validatorFactory = validatorFactory,
                    constraintValidatorManager = constraintValidatorManager,
                    constraintDescriptor = constraintDescriptor,
                    clazz = clazz,
                    value = value
                )
            when (constraintValidator) {
                null -> {
                    val configuration = context.path.toString().ifEmpty { clazz.simpleName }
                    throw UnexpectedTypeException(
                        "No validator could be found for constraint '${constraintDescriptor.annotation.annotationClass}'" +
                                " validating type '${clazz.name}'. " + "Check configuration for '$configuration'"
                    )
                }

                else -> {
                    // create validator context
                    val constraintValidatorContext: ConstraintValidatorContext =
                        constraintValidatorContextFactory
                            .newConstraintValidatorContext(
                                path = context.path,
                                constraintDescriptor = constraintDescriptor
                            )
                    // compute if valid
                    if (constraintValidator.isValid(value, constraintValidatorContext)) emptySet()
                    else {
                        constraintViolationHelper.buildConstraintViolations(
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
        } else emptySet()
    }

    /** Validate cascaded field-level properties */
    private fun <T : Any> validateCascadedProperty(
        context: ValidationContext<T>,
        isCollection: Boolean,
        clazz: Class<*>,
        clazzInstance: Any,
        groups: List<Class<*>>
    ): Set<ConstraintViolation<T>> {
        val results = mutableListOf<ConstraintViolation<T>>()
        if (clazz.kotlin.isData) { // only cascade into data classes; TODO("handle java records?")
            val descriptor = descriptorFactory.describe(clazz = clazz)
            val path = PathImpl.createCopy(context.path)
            if (isCollection) {
                val collectionValue: Iterable<*> = clazzInstance as Iterable<*>
                val collectionValueIterator = collectionValue.iterator()
                var index = 0
                while (collectionValueIterator.hasNext()) {
                    val instanceValue = collectionValueIterator.next()
                    // apply the index to the parent path, then use this to recompute paths of members and methods
                    val indexedPath = PathImpl.createCopyWithoutLeafNode(path)
                    indexedPath.addPropertyNode("${path.leafNode.asString()}[${index}]")

                    val violations = validateDescriptor(
                        descriptor = descriptor,
                        context = context.copy(path = indexedPath),
                        value = instanceValue,
                        groups = groups
                    )
                    if (violations.isNotEmpty()) results.addAll(violations)
                    index += 1
                }
            } else {
                results.addAll(
                    validateDescriptor(
                        descriptor = descriptor,
                        context = context.copy(path = path),
                        value = clazzInstance,
                        groups = groups
                    )
                )
            }
        }

        return results.toSet()
    }


    /** Invoke method and validate result */
    private fun <T : Any> executePostConstructValidations(
        context: ValidationContext<T>,
        method: Method,
        methodDescriptor: MethodDescriptor,
        clazzInstance: Any?,
        groups: List<Class<*>>
    ): Set<ConstraintViolation<T>> {
        val constraintDescriptor: ConstraintDescriptorImpl<PostConstructValidation> = methodDescriptor
            .returnValueDescriptor
            .constraintDescriptors.find { it.annotation.eq<PostConstructValidation>() } as? ConstraintDescriptorImpl<PostConstructValidation>
            ?: throw ValidationException("Cannot find @${PostConstructValidation::class.simpleName} annotation on ${method.declaringClass::class.simpleName}#${method.name}()")
        return executePostConstructValidations(
            context = context,
            method = method,
            constraintDescriptor = constraintDescriptor,
            clazzInstance = clazzInstance,
            groups = groups
        )
    }

    private fun <T : Any> executePostConstructValidations(
        context: ValidationContext<T>,
        method: Method,
        constraintDescriptor: ConstraintDescriptorImpl<PostConstructValidation>,
        clazzInstance: Any?,
        groups: List<Class<*>>
    ): Set<ConstraintViolation<T>> {
        return if (groupsEnabled(constraintDescriptor, groups) && clazzInstance != null) {
            try {
                val postConstructValidationResult = method.invoke(clazzInstance) as PostConstructValidationResult
                val pathWithMethodName = PathImpl.createCopy(context.path)
                val path = if (context.path.leafNode.name == method.name) {
                    // don't update the path, the leaf is already the method name.
                    pathWithMethodName
                } else {
                    pathWithMethodName.addPropertyNode(method.name)
                    pathWithMethodName
                }

                validatePostConstructValidation(
                    context = context,
                    clazzInstance = clazzInstance,
                    path = path,
                    constraintDescriptor,
                    constraintDescriptor.annotation,
                    postConstructValidationResult
                )
            } catch (e: InvocationTargetException) {
                if (e.cause != null) throw e.cause!! else throw e
            }
        } else emptySet()
    }

    private fun <T : Any> validateClazz(
        context: ValidationContext<T>,
        descriptor: BeanDescriptor,
        clazzInstance: Any?,
        groups: List<Class<*>>
    ): Set<ConstraintViolation<T>> {
        val constraintDescriptors: List<ConstraintDescriptor<*>> = descriptor.constraintDescriptors.toList()
        return if (constraintDescriptors.isNotEmpty()) {
            val results = mutableListOf<ConstraintViolation<T>>()
            var index = 0
            val length = constraintDescriptors.size
            while (index < length) {
                results.addAll(
                    isValid(
                        context = context,
                        constraintDescriptor = (constraintDescriptors[index] as ConstraintDescriptorImpl<Annotation>),
                        clazz = descriptor.elementClass as Class<out T>,
                        value = clazzInstance,
                        groups = groups
                    )
                )
                index += 1
            }
            results.toSet()
        } else emptySet()
    }

    private tailrec fun <T : Any> validateDescriptor(
        descriptor: ElementDescriptor?,
        context: ValidationContext<T>,
        value: Any?,
        groups: List<Class<*>>
    ): Set<ConstraintViolation<T>> = when (descriptor) {
        null -> {
            val clazz: Class<T> = value?.javaClass as Class<T>
            if (!value::class.isData) throw ValidationException("$clazz is not a valid data class.")
            validateDescriptor(
                descriptor = descriptorFactory.describe(clazz),
                context = ValidationContext(
                    fieldName = null,
                    rootClazz = clazz,
                    root = value as T,
                    leaf = value,
                    path = PathImpl.createRootPath()
                ),
                value = value,
                groups = groups
            )
        }

        is PropertyDescriptor -> {
            val propertyPath = PathImpl.createCopy(context.path)
            context.fieldName?.let { propertyPath.addPropertyNode(it) }
            // validateField will recurse back through validate for cascaded properties
            validateField(
                context.copy(path = propertyPath),
                descriptor,
                value,
                groups
            )
        }

        is BeanDescriptor -> {
            val propertyViolationResults = mutableListOf<ConstraintViolation<T>>()
            if (descriptor.constrainedProperties.isNotEmpty()) {
                val iterator = descriptor.constrainedProperties.iterator()
                while (iterator.hasNext()) {
                    val propertyDescriptor = iterator.next()
                    val propertyPath = PathImpl.createCopy(context.path)
                    propertyPath.addPropertyNode(propertyDescriptor.propertyName)
                    // validateField will recurse back through validate here for cascaded properties
                    val fieldResults =
                        validateField(
                            context = context.copy(
                                fieldName = propertyDescriptor.propertyName,
                                path = propertyPath
                            ),
                            propertyDescriptor = propertyDescriptor,
                            fieldValue = findFieldValue(
                                value,
                                descriptor.elementClass,
                                propertyDescriptor.propertyName
                            ),
                            groups = groups
                        )
                    if (fieldResults.isNotEmpty()) propertyViolationResults.addAll(fieldResults)
                }
            }

            val postConstructViolationsResults = mutableListOf<ConstraintViolation<T>>()
            val methodDescriptors = descriptor.getConstrainedMethods(MethodType.NON_GETTER)
            if (methodDescriptors.isNotEmpty()) {
                val iterator = methodDescriptors.iterator()
                while (iterator.hasNext()) {
                    val methodDescriptor = iterator.next()
                    if (isPostConstructValidationConstrainedMethod(methodDescriptor, value)) {
                        val method = descriptor.elementClass.getMethod(
                            /* name                 = */
                            methodDescriptor.name,
                            /* ...parameterTypes    = */
                            *methodDescriptor.parameterDescriptors.map { it.elementClass }.toTypedArray()
                        )

                        val methodResults =
                            executePostConstructValidations(
                                context = context.copy(
                                    fieldName = methodDescriptor.name
                                ),
                                method = method,
                                methodDescriptor = methodDescriptor,
                                clazzInstance = value,
                                groups = groups
                            )

                        if (methodResults.isNotEmpty()) postConstructViolationsResults.addAll(methodResults)
                    }
                }
            }

            val clazzViolations = validateClazz(context, descriptor, value, groups)

            // put them all together
            propertyViolationResults.toSet() + postConstructViolationsResults.toSet() + clazzViolations
        }

        else ->
            throw ValidationException("Invalid descriptor type: ${descriptor::class.java.name}")
    }

    // END: Recursive validation methods -------------------------------------------------------------------------------

    private fun <T : Any> validateReturnValue(
        context: ValidationContext<T>,
        executableDescriptor: ExecutableDescriptor,
        value: Any?,
        groups: List<Class<*>>
    ): Set<ConstraintViolation<T>> {
        val results = mutableSetOf<ConstraintViolation<T>>()
        val returnValueConstraintDescriptors = executableDescriptor.returnValueDescriptor.constraintDescriptors
        val iterator = returnValueConstraintDescriptors.iterator()
        while (iterator.hasNext()) {
            val returnValueConstraintDescriptor = iterator.next() as ConstraintDescriptorImpl<*>
            results.addAll(
                validateConstraintDescriptor(
                    context = context,
                    constraintDescriptor = returnValueConstraintDescriptor,
                    clazz = executableDescriptor.returnValueDescriptor.elementClass,
                    value = value,
                    groups = groups
                )
            )
        }

        return results.toSet()
    }

    private fun <T : Any> validateParameters(
        obj: T?,
        executable: Executable,
        executableDescriptor: ExecutableDescriptor,
        parameterValues: Array<Any?>,
        groups: List<Class<*>>
    ): Set<ConstraintViolation<T>> {
        if (parameterValues.size != executable.parameterCount) {
            val executableAsString =
                ExecutableHelper.getExecutableAsString(
                    /* name                 = */ executableDescriptor
                        .returnValueDescriptor
                        .elementClass.toString() + "#" + executableDescriptor.name,
                    /* ...parameterTypes    = */ *executable.parameterTypes
                )
            throw IllegalArgumentException(
                "Wrong number of parameters. Method or constructor $executableAsString " +
                        "expects ${executable.parameterCount} parameters, but got ${parameterValues.size}."
            )
        }

        val rootBeanClazz = obj?.javaClass ?: executable.declaringClass as Class<T>

        val results = mutableSetOf<ConstraintViolation<T>>()
        val parameterNames = DescriptorFactory.getExecutableParameterNames(executable)
        val size = parameterNames.size
        var index = 0
        while (index < size) {
            val parameterValue = parameterValues[index]
            val parameterName = parameterNames[index]
            val parameterDescriptor = executableDescriptor.parameterDescriptors.find { it.name == parameterName }
            when (parameterDescriptor) {
                null -> Unit // not constrained -- skip
                else -> {
                    parameterDescriptor.constraintDescriptors.forEach { constraintDescriptor: ConstraintDescriptor<*>? ->
                        val parameterPath = PathImpl.createPathForExecutable(getExecutableMetaData(executable))
                        val context = ValidationContext(
                            fieldName = parameterDescriptor.name,
                            rootClazz = rootBeanClazz,
                            root = obj,
                            leaf = obj,
                            path = parameterPath
                        )

                        if (constraintDescriptor != null) {
                            parameterPath.addParameterNode(parameterDescriptor.name, index)
                            results.addAll(
                                validateConstraintDescriptor(
                                    context = context.copy(path = parameterPath),
                                    constraintDescriptor = constraintDescriptor,
                                    clazz = parameterDescriptor.elementClass,
                                    value = parameterValue,
                                    groups = groups.toList()
                                )
                            )
                        }
                        // Cannot cascade a null value
                        if (parameterValue != null) {
                            if (parameterDescriptor.isCascaded) {
                                results.addAll(
                                    if (parameterDescriptor.constrainedContainerElementTypes.isNotEmpty()) {
                                        // need to cascade the constrained container element type, multi type containers are not supported
                                        // thus we only read the first constrained container element type
                                        validateCascadedProperty(
                                            context = context,
                                            isCollection = true,
                                            clazz = parameterDescriptor.constrainedContainerElementTypes.first().elementClass,
                                            clazzInstance = parameterValue,
                                            groups = groups
                                        )
                                    } else {
                                        validateCascadedProperty(
                                            context = context,
                                            isCollection = false,
                                            clazz = parameterDescriptor.elementClass,
                                            clazzInstance = parameterValue,
                                            groups = groups
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }

            index += 1
        }

        // cross-parameter validation
        if (executableDescriptor.crossParameterDescriptor != null) {
            val executablePath = PathImpl.createPathForExecutable(getExecutableMetaData(executable))
            executablePath.addCrossParameterNode()

            executableDescriptor.crossParameterDescriptor.constraintDescriptors.forEach { constraintDescriptor ->
                results.addAll(
                    validateConstraintDescriptor(
                        context = ValidationContext(
                            fieldName = executableDescriptor.name,
                            rootClazz = rootBeanClazz,
                            root = obj,
                            leaf = obj,
                            path = executablePath
                        ),
                        constraintDescriptor = constraintDescriptor,
                        clazz = executableDescriptor.crossParameterDescriptor.elementClass,
                        value = parameterValues,
                        groups = groups.toList()
                    )
                )
            }
        }

        return results.toSet()
    }

    private fun isPostConstructValidationConstrainedMethod(methodDescriptor: MethodDescriptor?, value: Any?): Boolean =
        methodDescriptor != null && value != null &&
                (methodDescriptor.returnValueDescriptor != null &&
                        methodDescriptor
                            .returnValueDescriptor
                            .constraintDescriptors
                            .any { it.annotation.eq<PostConstructValidation>() })

    private fun <T : Any> validatePostConstructValidation(
        context: ValidationContext<T>,
        clazzInstance: Any,
        path: PathImpl,
        constraintDescriptor: ConstraintDescriptorImpl<PostConstructValidation>,
        constraint: PostConstructValidation,
        returnValue: PostConstructValidationResult
    ): Set<ConstraintViolation<T>> {
        val results = mutableSetOf<ConstraintViolation<T>>()
        if (returnValue.isInstanceOf<PostConstructValidationResult.Invalid>()) {
            val invalidResult = returnValue as PostConstructValidationResult.Invalid
            val annotationFields: List<String> = constraint.fields.filter { it.isNotEmpty() }
            if (annotationFields.isNotEmpty()) {
                var index = 0
                val length = annotationFields.size
                while (index < length) {
                    val fieldName = annotationFields[index]
                    val parameterPath = PathImpl.createCopy(path)
                    parameterPath.addParameterNode(fieldName, index)
                    results.add(
                        constraintViolationHelper.newPostConstructValidationConstraintViolation(
                            constraint = constraint,
                            message = invalidResult.message,
                            path = parameterPath,
                            invalidValue = ClassHelper.getFieldValue(clazzInstance, fieldName),
                            rootClazz = context.rootClazz as Class<T>,
                            root = context.root,
                            leaf = clazzInstance,
                            constraintDescriptor = constraintDescriptor,
                            payload = invalidResult.payload
                        )
                    )
                    index += 1
                }
            } else {
                results.add(
                    constraintViolationHelper.newPostConstructValidationConstraintViolation(
                        constraint = constraint,
                        message = invalidResult.message,
                        path = path,
                        invalidValue = clazzInstance,
                        rootClazz = context.rootClazz as Class<T>,
                        root = context.root,
                        leaf = clazzInstance,
                        constraintDescriptor = constraintDescriptor,
                        payload = invalidResult.payload
                    )
                )
            }

        }
        return results.toSet()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> validateConstraintDescriptor(
        context: ValidationContext<T>,
        clazz: Class<*>,
        constraintDescriptor: ConstraintDescriptor<out Annotation>,
        value: Any?,
        groups: List<Class<*>>
    ): Set<ConstraintViolation<T>> {
        val results = mutableSetOf<ConstraintViolation<T>>()

        if (isValidationEnabled(value, constraintDescriptor, groups)) {
            val validators: Set<ConstraintValidator<Annotation, Any>> =
                ConstraintValidatorFactoryHelper.findInitializedConstraintValidator(
                    context = context,
                    validatorFactory = validatorFactory,
                    constraintValidatorManager = constraintValidatorManager,
                    constraintDescriptor = constraintDescriptor as ConstraintDescriptorImpl<Annotation>,
                    clazz = clazz,
                    value = value
                )

            val constraintValidatorContext: ConstraintValidatorContext =
                constraintValidatorContextFactory
                    .newConstraintValidatorContext(context.path, constraintDescriptor)

            if (validators.isEmpty()) throw UnexpectedTypeException(
                "No validator could be found for constraint '${constraintDescriptor.annotation.annotationClass}' " +
                        "validating type '${clazz.name}'. " +
                        "Check configuration for '${context.path}'")

            validators.forEach { validator ->
                if (!validator.isValid(value, constraintValidatorContext)) {
                    results.addAll(
                        constraintViolationHelper.buildConstraintViolations(
                            rootClazz = context.rootClazz,
                            root = context.root,
                            leaf = context.leaf,
                            path = context.path,
                            invalidValue = value,
                            constraintDescriptor = constraintDescriptor,
                            constraintValidatorContext = constraintValidatorContext
                        )
                    )
                }
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

    /** The value isn't ignorable, and the groups are enabled for the given constraint descriptor */
    private fun isValidationEnabled(
        value: Any?,
        constraintDescriptor: ConstraintDescriptor<*>,
        groups: List<Class<*>>
    ): Boolean =
        (!ignorable(value, constraintDescriptor.annotation) && groupsEnabled(constraintDescriptor, groups))

    private val getExecutableMetaData = ::getExecutableMetaDataFn.memoize()

    /** @note this method is memoized as it should only ever need to be calculated once for a given [KFunction] */
    private fun getExecutableMetaDataFn(executable: Executable): ExecutableMetaData {
        val callable = when (executable) {
            is Constructor<*> ->
                JavaBeanFactory.newJavaBeanConstructor(executable)

            is Method ->
                JavaBeanFactory.newJavaBeanMethod(executable)

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
            validatorFactory.constraintCreationContext,
            validatorFactory.executableHelper,
            validatorFactory.executableParameterNameProvider,
            validatorFactory.methodValidationConfiguration
        )

        return builder.build()
    }

    private fun <T : Any> findFieldValue(
        obj: Any?,
        clazz: Class<T>,
        name: String
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
}

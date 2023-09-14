package angstromio.validation

import angstromio.util.control.NonFatal.tryOrNull
import angstromio.util.extensions.Annotations.find
import angstromio.util.extensions.Annotations.getAnnotatedTypeAnnotations
import angstromio.util.extensions.Annotations.merge
import angstromio.util.reflect.Annotations
import angstromio.validation.constraints.PostConstructValidation
import angstromio.validation.engine.PostConstructValidationResult
import angstromio.validation.internal.ConstraintValidatorFactoryHelper
import angstromio.validation.internal.engine.ClassHelper
import angstromio.validation.internal.metadata.descriptor.ConstraintDescriptorFactory
import arrow.core.Ior
import arrow.core.memoize
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import jakarta.validation.ConstraintDeclarationException
import jakarta.validation.ConstraintTarget
import jakarta.validation.ConstraintValidator
import jakarta.validation.GroupSequence
import jakarta.validation.Valid
import jakarta.validation.constraintvalidation.SupportedValidationTarget
import jakarta.validation.constraintvalidation.ValidationTarget
import jakarta.validation.groups.Default
import jakarta.validation.metadata.BeanDescriptor
import jakarta.validation.metadata.CascadableDescriptor
import jakarta.validation.metadata.ConstructorDescriptor
import jakarta.validation.metadata.ContainerDescriptor
import jakarta.validation.metadata.ContainerElementTypeDescriptor
import jakarta.validation.metadata.ElementDescriptor
import jakarta.validation.metadata.ExecutableDescriptor
import jakarta.validation.metadata.GroupConversionDescriptor
import jakarta.validation.metadata.MethodDescriptor
import jakarta.validation.metadata.ParameterDescriptor
import jakarta.validation.metadata.PropertyDescriptor
import org.hibernate.validator.internal.engine.ValidatorFactoryInspector
import org.hibernate.validator.internal.metadata.descriptor.BeanDescriptorImpl
import org.hibernate.validator.internal.metadata.descriptor.ContainerElementTypeDescriptorImpl
import org.hibernate.validator.internal.metadata.descriptor.ExecutableDescriptorImpl
import org.hibernate.validator.internal.metadata.descriptor.ParameterDescriptorImpl
import org.hibernate.validator.internal.metadata.descriptor.PropertyDescriptorImpl
import org.hibernate.validator.internal.metadata.descriptor.ReturnValueDescriptorImpl
import org.hibernate.validator.internal.metadata.raw.ConstrainedElement
import org.hibernate.validator.internal.properties.Signature
import org.hibernate.validator.internal.properties.javabean.JavaBeanFactory
import java.lang.reflect.AnnotatedParameterizedType
import java.lang.reflect.AnnotatedType
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Parameter
import java.lang.reflect.Type
import kotlin.reflect.KCallable
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.javaConstructor
import kotlin.reflect.jvm.jvmName
import kotlin.reflect.jvm.kotlinFunction

internal class DescriptorFactory(
    descriptorCacheSize: Long,
    private val validatorFactory: ValidatorFactoryInspector,
    private val constraintDescriptorFactory: ConstraintDescriptorFactory
) {

    companion object {
        val IgnoredMethodNames: List<String> = listOf("equals", "copy", "hashCode", "toString", "copy${'$'}default")

        internal val getExecutableParameterNames = ::getExecutableParameterNamesFn.memoize()
        internal val getKFunctionParameterNames = ::getKFunctionParameterNamesFn.memoize()

        /** @note this method is memoized as it should only ever need to be calculated once for a given [KFunction] */
        private fun getKFunctionParameterNamesFn(kFunction: KFunction<*>): List<String> =
            kFunction.parameters.filter { it.kind == KParameter.Kind.VALUE }.map { it.name!! }

        private fun getExecutableParameterNamesFn(executable: Executable): List<String> =
            when (executable) {
                is Method ->
                    getKFunctionParameterNamesFn(executable.kotlinFunction!!)

                is Constructor<*> ->
                    getKFunctionParameterNamesFn(executable.kotlinFunction!!)
            }

        private val DefaultGroupsList: List<Class<*>> = listOf(Default::class.java)

        private fun isCascadedValidation(annotations: Array<Annotation>): Boolean =
            annotations.find<Valid>() != null

        @Suppress("UNCHECKED_CAST")
        private fun findGroupSequenceValues(clazz: Class<*>): List<Class<*>> {
            val groupSequenceAnnotation = clazz.annotations.find<GroupSequence>()
            return if (groupSequenceAnnotation != null) {
                val groupSequenceAnnotationValueMethod =
                    tryOrNull { groupSequenceAnnotation.annotationClass.java.getDeclaredMethod("value") }
                if (groupSequenceAnnotationValueMethod != null) {
                    (groupSequenceAnnotationValueMethod.invoke(groupSequenceAnnotation) as Array<Class<*>>).toList()
                } else DefaultGroupsList
            } else DefaultGroupsList
        }
    }

    private val dataClassDescriptorsCache: Cache<Class<*>, BeanDescriptor> =
        Caffeine
            .newBuilder()
            .maximumSize(descriptorCacheSize)
            .build()

    fun close() {
        dataClassDescriptorsCache.invalidateAll()
        dataClassDescriptorsCache.cleanUp()
    }

    /**
     * Describe a [KClass].
     *
     * @note the returned [BeanDescriptor] is cached for repeated lookup attempts keyed by
     *       the given KClass<T< type.
     */
    fun <T : Any> describe(clazz: Class<T>): BeanDescriptor {
        return (dataClassDescriptorsCache.get(clazz) { buildDescriptor(clazz) })
    }

    /**
     * Describe a [Constructor] (passed as a [KCallable]).
     *
     * @note the returned [ExecutableDescriptor] is NOT cached. It is up to the caller of this
     *       method to optimize any calls to this method.
     */
    fun <T : Any> describeConstructor(constructor: Constructor<T>): ConstructorDescriptor? =
        buildConstructorDescriptor(emptyMap(), constructor)

    /**
     * Describe a constrained [Method].
     *
     * As we do not want to describe every possible data class method, this potentially
     * returns a null in the case where the method has no constraint annotation and no
     * constrained parameters.
     *
     * @note the returned [ExecutableDescriptor] is NOT cached. It is up to the caller of this
     *       method to optimize any calls to this method.
     */
    fun describeMethod(method: Method): MethodDescriptor? = buildMethodDescriptor(emptyMap(), method)

    /**
     * Describe an "executable" given an optional "mix-in" Class.
     *
     * @note the returned [ExecutableDescriptor] is NOT cached. It is up to the caller of this
     *       method to optimize any calls to this method.
     */
    fun describe(
        executable: Executable,
        mixinClazz: Class<*>?
    ): ExecutableDescriptor? {
        val annotationMap =
            mixinClazz?.declaredMethods?.associate { mixinFunction ->
                mixinFunction.name to mixinFunction.annotations
            } ?: emptyMap()
        return when (executable) {
            is Method ->
                buildMethodDescriptor(annotationMap, executable)

            is Constructor<*> ->
                buildConstructorDescriptor(annotationMap, executable)

            else ->
                throw IllegalArgumentException()
        }
    }

    fun describeMethods(clazz: Class<*>): List<MethodDescriptor> {
        val clazzMethods = clazz.declaredMethods
        return if (clazzMethods.isNotEmpty()) {
            val methods = mutableListOf<MethodDescriptor>()
            for (method in clazzMethods) {
                val methodDescriptor = buildMethodDescriptor(emptyMap(), method)
                if (methodDescriptor != null) methods.add(methodDescriptor)
            }
            methods.toList()
        } else emptyList()
    }

    private fun buildDescriptor(clazz: Class<*>): BeanDescriptor {
        val groups: List<Class<*>> = findGroupSequenceValues(clazz).toList()
        val kClazz = clazz.kotlin

        val kotlinConstructors = kClazz.constructors
        val kotlinConstructor = kClazz.primaryConstructor
            ?: if (kotlinConstructors.isNotEmpty()) {
                kotlinConstructors.first()
            } else null

        // collect all possible field/property/member annotations:
        // 1. from constructor params -- this is the discriminating list of param names, e.g., the next two are filtered by these names
        // 2. from java declared fields for @field:Annotations
        // 3. from fields represented as java declared methods for @get:Annotations (@set:Annotation do not convey)
        val fieldAnnotationsMap: MutableMap<String, Array<Annotation>> =
            (kotlinConstructor?.let { constructor ->
                Annotations.getConstructorAnnotations(
                    clazz,
                    constructor.javaConstructor!!.parameterTypes
                )
            } ?: emptyMap()).toMutableMap()     // 1. from constructor params
        val fields = clazz.declaredFields       // 2. from java declared fields
        for (field in fields) {
            if (fieldAnnotationsMap[field.name] != null) {
                val current = fieldAnnotationsMap[field.name]
                fieldAnnotationsMap[field.name] =
                    current.merge(field.annotations)
            }
        }
        // check declared methods
        val declaredMethods = clazz.declaredMethods.filterNot { IgnoredMethodNames.contains(it.name) }
        for (method in declaredMethods) {
            val methodAsFieldName = ClassHelper.unMaskMethodName(method.name)
            if (fieldAnnotationsMap[methodAsFieldName] != null) {
                val current = fieldAnnotationsMap[methodAsFieldName] ?: emptyArray()
                fieldAnnotationsMap[methodAsFieldName] = current.merge(method.annotations)
            }
            checkConstrainedDeclaredMethod(method) // ensure there's no incorrectly specified method
            checkPostConstructValidationMethod(fieldAnnotationsMap[methodAsFieldName].merge(method.annotations), method)
        }

        return BeanDescriptorImpl(
            /* beanClass = */ clazz,
            /* classLevelConstraints = */
            clazz.annotations
                .filter { isConstraintAnnotation(it) }
                .map { annotation ->
                    constraintDescriptorFactory.newConstraintDescriptor(
                        constrainable = constraintDescriptorFactory.mkConstrainable(
                            name = null,
                            clazz = clazz,
                            declaringClazz = clazz,
                            constrainedElementKind = ConstrainedElement.ConstrainedElementKind.TYPE
                        ),
                        annotation = annotation
                    )
                }.toSet(),
            /* constrainedProperties = */
            buildPropertyDescriptors(fieldAnnotationsMap, clazz.declaredFields, groups),
            /* constrainedMethods = */
            buildMethodDescriptors(fieldAnnotationsMap, clazz.declaredMethods, groups),
            /* constrainedConstructors = */
            buildConstructors(fieldAnnotationsMap, clazz.constructors, groups),
            /* defaultGroupSequenceRedefined = */
            groups != DefaultGroupsList,
            /* defaultGroupSequence = */
            groups
        )
    }

    private fun buildConstructors(
        annotationMap: Map<String, Array<Annotation>>,
        constructors: Array<Constructor<*>>,
        groups: List<Class<*>>
    ): Map<Signature, ConstructorDescriptor> {
        val results = mutableMapOf<Signature, ConstructorDescriptor>()
        constructors.forEach { constructor: Constructor<*> ->
            val constructorDescriptor = buildConstructorDescriptor(annotationMap, constructor, groups)
            if (constructorDescriptor != null) {
                val signature = Signature(constructor.declaringClass.simpleName, *constructor.parameterTypes)
                results[signature] = constructorDescriptor
            }
        }

        return results.toMap()
    }


    private fun <T : Any> buildConstructorDescriptor(
        annotationMap: Map<String, Array<Annotation>>,
        constructor: Constructor<T>,
        groups: List<Class<*>> = DefaultGroupsList
    ): ConstructorDescriptor? {
        // left is cross-parameter, right is return value
        fun getAnnotations(): Ior<Array<Annotation>, Array<Annotation>> {
            val constructorAnnotationsList = constructor.annotations.filter { isConstraintAnnotation(it) }
            val constructorAnnotations = Array(constructorAnnotationsList.size) { index -> constructorAnnotationsList[index] }
            // Easy cases for determining where Method applies:
            // constructor with no parameters (the constraint applies to the constructor return value)
            // neither a method nor a constructor, but a field, parameter etc. (the constraint applies to the annotated element)
            // Harder cases:
            // in Annotation: validationAppliesTo = ConstraintTarget.PARAMETERS, ConstraintTarget.RETURN_VALUE
            // in Validator: @SupportedValidationTarget(value = [ValidationTarget.PARAMETERS])
            return if (constructor.parameters.isEmpty()) {
                // all annotations apply to return value
                Ior.Both(emptyArray<Annotation>(), constructorAnnotations)
            } else {
                val parameterAnnotations =
                    findAnnotationsForConstraintTarget(ConstraintTarget.PARAMETERS, constructorAnnotations) +
                            findAnnotationsForValidationTarget(ValidationTarget.PARAMETERS, constructorAnnotations)
                val returnValueAnnotations =
                    findAnnotationsForConstraintTarget(ConstraintTarget.RETURN_VALUE, constructorAnnotations) +
                            findAnnotationsForValidationTarget(
                                ValidationTarget.ANNOTATED_ELEMENT,
                                constructorAnnotations
                            )
                Ior.Both(parameterAnnotations, returnValueAnnotations)
            }
        }

        val annotationsIor: Ior<Array<Annotation>, Array<Annotation>> = getAnnotations()
        val parameterNames = if (constructor.kotlinFunction != null) {
            getKFunctionParameterNames(constructor.kotlinFunction!!)
        } else emptyList()

        val crossParameterConstraints = (annotationsIor.leftOrNull() ?: emptyArray())
            .filter { isConstraintAnnotation(it) }
            .map { annotation ->
                constraintDescriptorFactory.newConstraintDescriptor(
                    constrainable = JavaBeanFactory.newJavaBeanConstructor(constructor),
                    annotation = annotation
                )
            }.toSet()
        val returnValueDescriptor = buildReturnValueDescriptor(
            annotations = (annotationsIor.getOrNull() ?: emptyArray()),
            executable = constructor,
            declaringClazz = constructor.declaringClass,
            returnType = constructor.declaringClass,
            annotatedReturnType = constructor.annotatedReturnType,
            groups = groups,
            shouldReturn = true // always return for a constructor
        )
        val parameterDescriptors = buildParameterDescriptors(
            annotationMap = annotationMap,
            declaringClazz = constructor.declaringClass,
            parameters = constructor.parameters,
            parameterNames = parameterNames,
            groups = groups
        )

        return if (crossParameterConstraints.isNotEmpty() || parameterDescriptors.isNotEmpty() || shouldReturnDescriptor(
                returnValueDescriptor
            )
        ) {
            ExecutableDescriptorImpl(
                /* returnType                       = */ constructor.annotatedReturnType.type,
                /* name                             = */constructor.declaringClass.simpleName,
                /* crossParameterConstraints        = */crossParameterConstraints,
                /* returnValueDescriptor            = */returnValueDescriptor,
                /* parameters                       = */parameterDescriptors,
                /* defaultGroupSequenceRedefined    = */groups != DefaultGroupsList,
                /* isGetter                         = */false, // constructor
                /* defaultGroupSequence             = */groups
            )
        } else null
    }

    private fun buildMethodDescriptors(
        annotationMap: Map<String, Array<Annotation>>,
        methods: Array<Method>,
        groups: List<Class<*>>
    ): Map<Signature, ExecutableDescriptorImpl> {
        val results = mutableMapOf<Signature, ExecutableDescriptorImpl>()
        methods.filterNot { IgnoredMethodNames.contains(it.name) }.forEach { method: Method ->
            val methodDescriptor = buildMethodDescriptor(annotationMap, method, groups)
            if (methodDescriptor != null) {
                val signature = Signature(method.name, *method.parameterTypes)
                results[signature] = methodDescriptor
            }
        }

        return results.toMap()
    }

    private fun buildMethodDescriptor(
        annotationMap: Map<String, Array<Annotation>>,
        method: Method,
        groups: List<Class<*>> = DefaultGroupsList
    ): ExecutableDescriptorImpl? {
        // left is cross-parameter, right is return value
        fun getAnnotations(): Ior<Array<Annotation>, Array<Annotation>> {
            val allMethodAnnotations: Array<Annotation> =
                annotationMap[ClassHelper.unMaskMethodName(method.name)].merge(method.annotations)
            // Easy cases for determining where Method applies:
            // a void method with parameters (the constraint applies to the parameters)
            // an executable with return value but no parameters (the constraint applies to the return value)
            // neither a method nor a constructor, but a field, parameter etc. (the constraint applies to the annotated element)
            // Harder cases:
            // in Annotation: validationAppliesTo = ConstraintTarget.PARAMETERS, ConstraintTarget.RETURN_VALUE
            // in Validator: @SupportedValidationTarget(value = [ValidationTarget.PARAMETERS])
            return if (method.returnType.equals(Void.TYPE)) {
                // all annotations apply to parameters (if any)
                Ior.Both(allMethodAnnotations, emptyArray())
            } else if (method.parameters.isEmpty()) {
                // all annotations apply to return value
                Ior.Both(emptyArray(), allMethodAnnotations)
            } else {
                val parameterAnnotations =
                    findAnnotationsForConstraintTarget(ConstraintTarget.PARAMETERS, allMethodAnnotations) +
                            findAnnotationsForValidationTarget(ValidationTarget.PARAMETERS, allMethodAnnotations)
                val returnValueAnnotations =
                    findAnnotationsForConstraintTarget(ConstraintTarget.RETURN_VALUE, allMethodAnnotations) +
                            findAnnotationsForValidationTarget(ValidationTarget.ANNOTATED_ELEMENT, allMethodAnnotations)
                Ior.Both(parameterAnnotations, returnValueAnnotations)
            }
        }

        val annotationsIor: Ior<Array<Annotation>, Array<Annotation>> = getAnnotations()
        val parameterNames = if (method.kotlinFunction != null) {
            getKFunctionParameterNames(method.kotlinFunction!!)
        } else emptyList()

        val crossParameterConstraints = (annotationsIor.leftOrNull() ?: emptyArray())
            .filter { isConstraintAnnotation(it) }
            .map { annotation ->
                constraintDescriptorFactory.newConstraintDescriptor(
                    constrainable = JavaBeanFactory.newJavaBeanMethod(method),
                    annotation = annotation
                )
            }.toSet()
        val returnValueDescriptor = buildReturnValueDescriptor(
            annotations = (annotationsIor.getOrNull() ?: emptyArray()),
            executable = method,
            declaringClazz = method.declaringClass,
            returnType = method.returnType,
            annotatedReturnType = method.annotatedReturnType,
            groups = groups
        )
        val parameterDescriptors =
            buildParameterDescriptors(emptyMap(), method.declaringClass, method.parameters, parameterNames, groups)

        return if (crossParameterConstraints.isNotEmpty() ||
            parameterDescriptors.isNotEmpty() ||
            shouldReturnDescriptor(returnValueDescriptor)
        ) {
            ExecutableDescriptorImpl(
                /* returnType                       = */ method.returnType,
                /* name                             = */ method.name,
                /* crossParameterConstraints        = */ crossParameterConstraints,
                /* returnValueDescriptor            = */ returnValueDescriptor,
                /* parameters                       = */ parameterDescriptors,
                /* defaultGroupSequenceRedefined    = */ groups != DefaultGroupsList,
                /* isGetter                         = */ method.name.startsWith("get"),
                /* defaultGroupSequence             = */ groups
            )
        } else null
    }

    private fun buildPropertyDescriptors(
        annotationMap: Map<String, Array<Annotation>>,
        fields: Array<Field>,
        groups: List<Class<*>>
    ): Map<String, PropertyDescriptor> {
        val results = mutableMapOf<String, PropertyDescriptor>()
        fields.forEach { field ->
            val annotations = annotationMap[field.name].merge(field.annotations)
            val propertyDescriptor = buildPropertyDescriptor(annotations, field, groups)
            if (propertyDescriptor != null) {
                results[propertyDescriptor.propertyName] = propertyDescriptor
            }
        }

        return results.toMap()
    }

    private fun buildPropertyDescriptor(
        annotations: Array<Annotation>,
        field: Field,
        groups: List<Class<*>>
    ): PropertyDescriptor? {
        val isCascaded = isCascadedValidation(annotations)

        val constrainedElementTypes = getContainerElementTypeDescriptorsForAnnotatedType(
            constraintDescriptorFactory = constraintDescriptorFactory,
            declaringClazz = field.declaringClass,
            clazz = field.type,
            annotatedType = field.annotatedType,
            elementAnnotations = annotations,
            groups = groups
        )

        return if (constrainedElementTypes.isNotEmpty() || isConstrained(annotations) || isCascaded) {
            PropertyDescriptorImpl(
                /* returnType = */ field.genericType,
                /* propertyName = */
                field.name,
                /* constraints = */
                annotations.filter { isConstraintAnnotation(it) }
                    .map { annotation ->
                        constraintDescriptorFactory.newConstraintDescriptor(
                            constrainable = JavaBeanFactory.newJavaBeanField(field, field.name),
                            annotation = annotation
                        )
                    }.toSet(),
                /* constrainedContainerElementTypes = */
                constrainedElementTypes,
                /* cascaded = */
                isCascadedValidation(/*field.type,*/ annotations),
                /* defaultGroupSequenceRedefined = */
                groups != DefaultGroupsList,
                /* defaultGroupSequence = */
                groups,
                /* groupConversions = */
                emptySet<GroupConversionDescriptor>()
            )
        } else null
    }

    private fun buildParameterDescriptors(
        annotationMap: Map<String, Array<Annotation>>,
        declaringClazz: Class<*>,
        parameters: Array<Parameter>,
        parameterNames: List<String>,
        groups: List<Class<*>>
    ): List<ParameterDescriptor> {
        val results = mutableListOf<ParameterDescriptor>()
        val size = parameters.size
        var index = 0

        while (index < size) {
            val parameter = parameters[index]
            val parameterName =
                if (parameterNames.isNotEmpty() && parameterNames[index].isNotEmpty()) parameterNames[index] else parameter.name

            val parameterDescriptor = buildParameterDescriptor(
                propertyIndex = index,
                name = parameterName,
                clazz = parameter.type,
                type = parameter.type as Type,
                declaringClazz = declaringClazz,
                annotatedType = parameter.annotatedType,
                annotations = parameter.annotations.merge(annotationMap[parameterName]),
                groups = groups
            )
            if (parameterDescriptor != null) results.add(parameterDescriptor)

            index += 1
        }
        return results.toList()
    }

    private fun buildParameterDescriptor(
        propertyIndex: Int,
        name: String,
        clazz: Class<out Any>,
        type: Type,
        declaringClazz: Class<*>,
        annotatedType: AnnotatedType,
        annotations: Array<Annotation>,
        groups: List<Class<*>> = DefaultGroupsList
    ): ParameterDescriptor? {
        val isCascaded = isCascadedValidation(annotations)

        val containerElementTypeDescriptors = getContainerElementTypeDescriptorsForAnnotatedType(
            constraintDescriptorFactory = constraintDescriptorFactory,
            declaringClazz = declaringClazz,
            clazz = clazz,
            annotatedType = annotatedType,
            elementAnnotations = annotations,
            groups = groups
        )

        val constraints = annotations
            .filter { isConstraintAnnotation(it) }
            .map { annotation ->
                constraintDescriptorFactory.newConstraintDescriptor(
                    name = name,
                    clazz = annotation.javaClass,
                    declaringClazz = declaringClazz,
                    annotation = annotation,
                    constrainedElementKind = ConstrainedElement.ConstrainedElementKind.FIELD
                )
            }.toSet()

        return if (containerElementTypeDescriptors.isNotEmpty() || constraints.isNotEmpty() || isCascaded) {
            ParameterDescriptorImpl(
                /* type = */ type,
                /* index = */
                propertyIndex,
                /* name = */
                name,
                /* constraints = */
                constraints,
                /* constrainedContainerElementTypes = */
                containerElementTypeDescriptors,
                /* isCascaded = */
                isCascadedValidation(/*clazz,*/ annotations/*.merge(annotatedType.annotations)*/),
                /* defaultGroupSequenceRedefined = */
                groups != DefaultGroupsList,
                /* defaultGroupSequence = */
                groups,
                /* groupConversions = */
                emptySet<GroupConversionDescriptor>()
            )
        } else null
    }


    private fun buildReturnValueDescriptor(
        annotations: Array<Annotation>,
        executable: Executable,
        declaringClazz: Class<*>,
        returnType: Class<*>,
        annotatedReturnType: AnnotatedType,
        groups: List<Class<*>>,
        shouldReturn: Boolean = false
    ): ReturnValueDescriptorImpl? {
        val returnValueConstraints = annotations
            .filter { isConstraintAnnotation(it) }
            .map { annotation ->
                val constrainable = when (executable) {
                    is Method -> JavaBeanFactory.newJavaBeanMethod(executable)
                    is Constructor<*> -> JavaBeanFactory.newJavaBeanConstructor(executable)
                }

                constraintDescriptorFactory.newConstraintDescriptor(
                    constrainable = constrainable,
                    annotation = annotation
                )
            }.toSet()

        val containerElementTypeDescriptors = getContainerElementTypeDescriptorsForAnnotatedType(
            constraintDescriptorFactory = constraintDescriptorFactory,
            declaringClazz = declaringClazz,
            clazz = returnType,
            annotatedType = annotatedReturnType,
            elementAnnotations = annotations,
            groups = groups
        )

        return if (shouldReturn || returnValueConstraints.isNotEmpty() || containerElementTypeDescriptors.isNotEmpty()) {
            ReturnValueDescriptorImpl(
                /* returnType                           = */ returnType,
                /* returnValueConstraints               = */ returnValueConstraints,
                /* constrainedContainerElementTypes     = */ containerElementTypeDescriptors,
                /* cascaded                             = */ false,
                /* defaultGroupSequenceRedefined        = */ groups != DefaultGroupsList,
                /* defaultGroupSequence                 = */ groups,
                /* groupConversions                     = */ emptySet<GroupConversionDescriptor>()
            )
        } else null
    }

    private fun shouldReturnDescriptor(descriptor: Any?): Boolean {
        return if (descriptor == null) {
            false
        } else {
            val isCascaded = if (CascadableDescriptor::class.java.isAssignableFrom(descriptor::class.java)) {
                (descriptor as CascadableDescriptor).isCascaded
            } else false

            val hasConstraints = if (ExecutableDescriptor::class.java.isAssignableFrom(descriptor::class.java)) {
                (descriptor as ExecutableDescriptor).hasConstraints()
            } else false

            val hasConstrainedContainerElements =
                if (ContainerDescriptor::class.java.isAssignableFrom(descriptor::class.java)) {
                    (descriptor as ContainerDescriptor).constrainedContainerElementTypes.isNotEmpty()
                } else false

            val hasConstraintDescriptors = if (ElementDescriptor::class.java.isAssignableFrom(descriptor::class.java)) {
                (descriptor as ElementDescriptor).constraintDescriptors.isNotEmpty()
            } else false

            isCascaded || hasConstraints || hasConstrainedContainerElements || hasConstraintDescriptors
        }
    }

    private fun findAnnotationsForConstraintTarget(
        discriminator: ConstraintTarget,
        annotations: Array<Annotation>
    ): Array<Annotation> {
        val annotationsList = annotations.filter { annotation ->
            val validationAppliesToMethod =
                tryOrNull { annotation.annotationClass.java.getDeclaredMethod("validationAppliesTo") }
            when (validationAppliesToMethod) {
                null -> false
                else -> {
                    val constraintTarget: ConstraintTarget =
                        validationAppliesToMethod.invoke(annotation) as ConstraintTarget
                    constraintTarget == discriminator
                }
            }
        }
        return Array(annotationsList.size) { index -> annotationsList[index] }
    }

    private fun findAnnotationsForValidationTarget(
        discriminator: ValidationTarget,
        annotations: Array<Annotation>
    ): Array<Annotation> {
        val annotationsList = annotations.filter { isConstraintAnnotation(it) }.filter { annotation ->
            val validators: Set<ConstraintValidator<*, *>> = ConstraintValidatorFactoryHelper.findConstraintValidators(
                validatorFactory = validatorFactory,
                annotationClazz = annotation.annotationClass.java
            )
            validators.any { validator ->
                val supportedValidationTargetAnnotation =
                    validator::class.java.getAnnotation(SupportedValidationTarget::class.java)
                val valueMethod =
                    tryOrNull { supportedValidationTargetAnnotation.annotationClass.java.getDeclaredMethod("value") }
                when (valueMethod) {
                    null -> false
                    else -> {
                        val validationTargetsArray = valueMethod.invoke(supportedValidationTargetAnnotation) as Array<*>
                        validationTargetsArray.contains(discriminator)
                    }
                }
            }
        }
        return Array(annotationsList.size) { index -> annotationsList[index] }
    }

    private fun checkConstrainedDeclaredMethod(method: Method) {
        if (method.returnType.equals(Void.TYPE) &&
            (method.annotations.any { isConstraintAnnotation(it) } || Annotations.findAnnotation<Valid>(method.annotations) != null)
        ) {
            val methodMessage =
                "${method.declaringClass.simpleName}#${method.name}(${method.parameterTypes.joinToString { p -> p.simpleName }})"
            throw ConstraintDeclarationException("HV000132: Void methods must not be constrained or marked for cascaded validation, but method $methodMessage is.")
        } else Unit
    }

    private fun checkPostConstructValidationMethod(annotations: Array<Annotation>?, method: Method) {
        val hasPostConstructValidationAnnotation =
            annotations != null && annotations.find<PostConstructValidation>() != null
        val methodMessage =
            "${method.declaringClass.simpleName}#${method.name}(${method.parameterTypes.joinToString { p -> p.simpleName }})"
        if (hasPostConstructValidationAnnotation && method.returnType != PostConstructValidationResult::class.java) {
            throw ConstraintDeclarationException("Methods annotated with @${PostConstructValidation::class.simpleName} must return a ${PostConstructValidationResult::class.simpleName}, but method $methodMessage does not.")
        }
        if (hasPostConstructValidationAnnotation && method.parameters.isNotEmpty()) {
            throw ConstraintDeclarationException("Methods annotated with @${PostConstructValidation::class.simpleName} must not declare any parameters, but method $methodMessage does.")
        }
    }

    // checks for any Constraint or @Valid annotation
    private fun isConstrainedAnnotatedType(annotatedType: AnnotatedType): Boolean {
        val annotations = annotatedType.getAnnotatedTypeAnnotations()
        return annotations.find<Valid>() != null || annotations.any { isConstraintAnnotation(it) }
    }

    private fun isConstraintAnnotation(annotation: Annotation): Boolean =
        validatorFactory.constraintHelper.isConstraintAnnotation(annotation.annotationClass.java)

    // we translate constraints on constructor parameters and getter|setter methods to fields of the same name.
    private fun isConstrainedField(annotationMap: Map<String, Array<Annotation>>, field: Field): Boolean {
        val annotations = annotationMap[field.name].merge(field.annotations)
        return isConstrained(annotations) || isCascadedValidation(annotations)
    }

    private fun isConstrained(annotations: Array<Annotation>): Boolean = annotations.any { isConstraintAnnotation(it) }

    private fun getContainerElementTypeDescriptorsForAnnotatedType(
        constraintDescriptorFactory: ConstraintDescriptorFactory,
        declaringClazz: Class<*>,
        clazz: Class<*>,
        annotatedType: AnnotatedType,
        elementAnnotations: Array<Annotation>,
        groups: List<Class<*>>
    ): Set<ContainerElementTypeDescriptor> {
        // Kotlin currently doesn't read annotations on the annotated type, so we need to look at the field annotations,
        // i.e., we cannot currently see the @Valid annotation on a type like List<@Valid Person>, so we instead
        // assume that the @Valid on the field pertains to the inside type, e.g. a field, @Valid val persons: List<Person>
        // is cascaded for the Person type.
        // TODO("patch when annotations on types are carried properly, e.g., List<@Valid Person>")
        val isCascaded = isCascadedValidation(elementAnnotations)
        val isConstrainedType = isConstrainedAnnotatedType(annotatedType) || isCascaded

        return if (isConstrainedType) {
            when (annotatedType) {
                is AnnotatedParameterizedType -> {
                    val annotatedActualTypeArguments = annotatedType.annotatedActualTypeArguments
                    val results = mutableSetOf<ContainerElementTypeDescriptor>()
                    val size = annotatedActualTypeArguments.size
                    var index = 0
                    while (index < size) {
                        val annotatedTypeArg = annotatedActualTypeArguments[index]
                        results.add(
                            getContainerElementTypeDescriptor(
                                constraintDescriptorFactory = constraintDescriptorFactory,
                                typeArgumentIndex = index,
                                declaringClazz = declaringClazz,
                                containerClazz = clazz,
                                annotatedType = annotatedTypeArg,
                                specifyAsCascaded = isCascaded,
                                groups = groups
                            )
                        )
                        index += 1
                    }
                    results.toSet()
                }

                else ->
                    emptySet()
            }
        } else emptySet()
    }

    // Given a class User with a property declared as Map<@Valid AddressType, @NotNull Address> addresses
    private fun getContainerElementTypeDescriptor(
        constraintDescriptorFactory: ConstraintDescriptorFactory,
        typeArgumentIndex: Int,
        declaringClazz: Class<*>,
        containerClazz: Class<*>,
        annotatedType: AnnotatedType,
        specifyAsCascaded: Boolean,
        groups: List<Class<*>>
    ): ContainerElementTypeDescriptor {
        fun getContainedContainerElementTypeDescriptors(annotatedType: AnnotatedType): Set<ContainerElementTypeDescriptor> {
            return when (annotatedType) {
                is AnnotatedParameterizedType -> {
                    val results = mutableSetOf<ContainerElementTypeDescriptor>()
                    val annotatedTypeArguments = annotatedType.annotatedActualTypeArguments
                    val size = annotatedTypeArguments.size
                    var index = 0
                    while (index < size) {
                        val actualTypeArg = annotatedTypeArguments[index]
                        results.add(
                            getContainerElementTypeDescriptor(
                                constraintDescriptorFactory,
                                index,
                                declaringClazz,
                                containerClazz,
                                actualTypeArg,
                                specifyAsCascaded,
                                groups
                            )
                        )
                        index += 1
                    }
                    results.toSet()
                }

                else -> emptySet()
            }
        }
        return ContainerElementTypeDescriptorImpl(
            /* type = */ annotatedType.type,
            /* containerClass = */
            containerClazz,
            /* typeArgumentIndex = */
            typeArgumentIndex,
            /* constraints = */
            annotatedType.getAnnotatedTypeAnnotations().filter { isConstraintAnnotation(it) }.map { annotation ->
                constraintDescriptorFactory.newConstraintDescriptor(
                    name = annotation.annotationClass.jvmName,
                    clazz = annotation.annotationClass.java,
                    declaringClazz = declaringClazz,
                    annotation = annotation,
                    constrainedElementKind = ConstrainedElement.ConstrainedElementKind.FIELD
                )
            }.toSet(),
            /* constrainedContainerElementTypes = */
            getContainedContainerElementTypeDescriptors(annotatedType),
            /* cascaded = */
            specifyAsCascaded || isCascadedValidation(annotatedType.getAnnotatedTypeAnnotations()),
            /* defaultGroupSequenceRedefined = */
            groups != DefaultGroupsList,
            /* defaultGroupSequence = */
            groups,
            /* groupConversions = */
            emptySet<GroupConversionDescriptor>()
        )
    }
}
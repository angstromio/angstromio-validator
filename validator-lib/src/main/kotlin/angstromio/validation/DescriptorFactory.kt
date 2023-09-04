package angstromio.validation

import angstromio.util.extensions.find
import angstromio.util.reflect.Annotations
import angstromio.validation.engine.MethodValidationResult
import angstromio.validation.metadata.DataClassDescriptor
import angstromio.validation.metadata.ExecutableDescriptor
import angstromio.validation.metadata.PropertyDescriptor
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import jakarta.validation.ConstraintDeclarationException
import jakarta.validation.Valid
import org.hibernate.validator.internal.metadata.core.ConstraintHelper
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import kotlin.reflect.KCallable
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.KType
import kotlin.reflect.full.createType
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.javaConstructor
import kotlin.reflect.jvm.javaMethod
import kotlin.reflect.jvm.jvmErasure

class DescriptorFactory(
    descriptorCacheSize: Long,
    private val constraintHelper: ConstraintHelper
) {

    companion object {
        private val IgnoredMemberFunctions: Set<String> = setOf("copy", "equals", "hashCode")

        private fun isCascadedValidation(kotlinType: KType?, annotations: List<Annotation>): Boolean {
            return if (kotlinType != null) {
                val erasedTyped = kotlinType.jvmErasure
                annotations.find<Valid>() != null && (erasedTyped == Any::class || erasedTyped.isData)
            } else false
        }

        fun getCascadedType(kotlinType: KType, annotations: List<Annotation>): KType? {
            val clazzType: Class<*> = kotlinType.jvmErasure.java
            return when {
                Map::class.java.isAssignableFrom(clazzType) ->
                    null // Maps not supportable
                Collection::class.java.isAssignableFrom(clazzType) || clazzType.isArray ->
                    if (isCascadedValidation(
                            kotlinType.arguments.first().type,
                            annotations
                        )
                    ) kotlinType.arguments.first().type
                    else null

                kotlinType == Any::class.createType() ->
                    Any::class.createType()

                else -> {
                    if (isCascadedValidation(kotlinType, annotations)) kotlinType
                    else null
                }
            }
        }
    }

    private val dataClassDescriptorsCache: Cache<KClass<*>, DataClassDescriptor<*>> =
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
     * @note the returned [DataClassDescriptor] is cached for repeated lookup attempts keyed by
     *       the given KClass<T< type.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> describe(clazz: KClass<T>): DataClassDescriptor<T> {
        return (dataClassDescriptorsCache.get(clazz) { buildDescriptor(clazz) }) as DataClassDescriptor<T>
    }

    /**
     * Describe a [Constructor] (passed as a [KCallable]).
     *
     * @note the returned [ExecutableDescriptor] is NOT cached. It is up to the caller of this
     *       method to optimize any calls to this method.
     */
    fun <T : Any> describeConstructor(constructor: KFunction<T>): ExecutableDescriptor<T> =
        buildConstructorDescriptor(constructor)

    /**
     * Describe a `@MethodValidation`-annotated or otherwise constrained [Method].
     *
     * As we do not want to describe every possible data class method, this potentially
     * returns a null in the case where the method has no constraint annotation and no
     * constrained parameters.
     *
     * @note the returned [ExecutableDescriptor] is NOT cached. It is up to the caller of this
     *       method to optimize any calls to this method.
     */
    fun <R : Any> describeMethod(method: KFunction<R>): ExecutableDescriptor<R>? = buildMethodDescriptor(method)

    /**
     * Describe an "executable" given an optional "mix-in" Class.
     *
     * @note the returned [ExecutableDescriptor] is NOT cached. It is up to the caller of this
     *       method to optimize any calls to this method.
     */
    fun <R : Any> describe(executable: KFunction<R>, mixinClazz: KClass<*>?): ExecutableDescriptor<R>? {
        val annotationMap =
            mixinClazz?.declaredMemberFunctions?.associate { mixinFunction ->
                mixinFunction.name to mixinFunction.annotations
            } ?: emptyMap()
        return when {
            executable.javaMethod != null ->
                buildMethodDescriptor(executable, annotationMap)

            executable.javaConstructor != null ->
                buildConstructorDescriptor(executable, annotationMap)

            else ->
                throw IllegalArgumentException()
        }
    }

    /**
     * Describe `@MethodValidation`-annotated or otherwise constrained methods of a given `KClass<*>>`.
     *
     * @note the returned [ExecutableDescriptor] instances are NOT cached. It is up to the caller of this
     *       method to optimize any calls to this method.
     */
    @Suppress("UNCHECKED_CAST")
    fun describeMethods(clazz: KClass<*>): List<ExecutableDescriptor<Any>> {
        val clazzMethods = clazz.declaredMemberFunctions
        return if (clazzMethods.isNotEmpty()) {
            val methods = mutableListOf<ExecutableDescriptor<Any>>()
            clazzMethods.stream().forEach { method ->
                val methodDescriptor = buildMethodDescriptor(method as KFunction<Any>)
                if (methodDescriptor != null) {
                    methods.add(methodDescriptor)
                }
            }
            methods.toList()
        } else emptyList()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> buildDescriptor(clazz: KClass<T>): DataClassDescriptor<T> {
        val members = mutableMapOf<String, PropertyDescriptor>()

        val constructors = mutableListOf<ExecutableDescriptor<T>>()

        // create executable descriptors
        for (constructor in clazz.constructors) {
            constructors.add(
                buildConstructorDescriptor(
                    constructor,
                    Annotations.getConstructorAnnotations(clazz, constructor.parameters.map { it.type.jvmErasure })
                )
            )
        }

        // collect all possible member annotations:
        // 1. from constructor params -- this is the discriminating list of param names, e.g., the next two are filtered by these names
        // 2. from java declared fields for @field:Annotations
        // 3. from fields represented as java declared methods for @get:Annotations
        val membersAnnotationMap: MutableMap<String, List<Annotation>> =
            (clazz.primaryConstructor?.let { constructor ->
                Annotations.getConstructorAnnotations(clazz, constructor.parameters.map { it.type.jvmErasure })
            } ?: emptyMap()).toMutableMap()     // 1. from constructor params
        val fields = clazz.java.declaredFields  // 2. from java declared fields
        for (field in fields) {
            if (membersAnnotationMap[field.name] != null) {
                val current = membersAnnotationMap[field.name]
                membersAnnotationMap[field.name] = (current!! + field.annotations).distinct()
            }
        }
        // check declared methods
        val declaredMethods = clazz.java.declaredMethods
        for (method in declaredMethods) {
            if (membersAnnotationMap[method.name] != null) {
                val current = membersAnnotationMap[method.name]
                membersAnnotationMap[method.name] = (current!! + method.annotations).distinct()
            }
            checkConstrainedDeclaredMethod(method) // ensure there's no incorrectly specified method
        }

        // create member property descriptors
        val memberProperties = clazz.declaredMemberProperties
        for (property in memberProperties) {
            members[property.name] = buildPropertyDescriptor(
                clazz = property.returnType.jvmErasure,
                kotlinType = property.returnType,
                annotations = ((membersAnnotationMap[property.name] ?: emptyList()) + property.annotations).distinct()
            )
        }

        // create method executable descriptors
        val methods = mutableListOf<ExecutableDescriptor<Any>>()
        val filteredMemberFunctions =
            filterMemberFunctions(clazz.memberFunctions) // also want to include any inherited functions
        for (method: KFunction<*> in filteredMemberFunctions) {
            val methodDescriptor = buildMethodDescriptor(method as KFunction<Any>)
            if (methodDescriptor != null) methods.add(methodDescriptor)
        }

        return DataClassDescriptor(
            clazz,
            clazz.java,
            clazz.annotations.filter { isConstraintAnnotation(it) },
            constructors,
            members,
            methods
        )
    }

    private fun filterMemberFunctions(collection: Collection<KFunction<*>>): List<KFunction<*>> =
        collection.filterNot { IgnoredMemberFunctions.contains(it.name) }

    private fun mkGetterNameMethod(methodName: String): String =
        if (methodName.startsWith("get")) {
            methodName.substring(3).replaceFirstChar { it.lowercase() }
        } else {
            methodName
        }

    private fun <T : Any> buildConstructorDescriptor(
        callable: KFunction<T>,
        annotationMap: Map<String, List<Annotation>>? = emptyMap()
    ): ExecutableDescriptor<T> {
        return ExecutableDescriptor(
            ExecutableDescriptor.Kind.Constructor,
            callable,
            annotations = callable.annotations.filter { isConstraintAnnotation(it) },
            members = callable.parameters.let { parameters ->
                parameters.filter { it.kind == KParameter.Kind.VALUE }.associate { parameter ->
                    parameter.name!! to buildPropertyDescriptor(
                        parameter.type.jvmErasure,
                        parameter.type,
                        (parameter.annotations + (annotationMap?.get(parameter.name) ?: emptyList())).distinct()
                    )
                }
            }
        )
    }

    private fun buildPropertyDescriptor(
        clazz: KClass<out Any>,
        kotlinType: KType,
        annotations: List<Annotation>
    ): PropertyDescriptor {
        val cascadedType = getCascadedType(kotlinType, annotations)
        return PropertyDescriptor(
            clazz,
            kotlinType,
            cascadedType,
            annotations.filter { isConstraintAnnotation(it) },
            cascadedType != null
        )
    }

    private fun <M : Any> buildMethodDescriptor(
        callable: KFunction<M>,
        annotationMap: Map<String, List<Annotation>>? = emptyMap()
    ): ExecutableDescriptor<M>? {
        return if (isMethodValidation(callable) && checkMethodValidationMethod(callable)) {
            ExecutableDescriptor(
                kind = ExecutableDescriptor.Kind.Method,
                callable = callable,
                annotations = callable.annotations.filter { isConstraintAnnotation(it) },
                members = emptyMap()
            )
        } else if (isConstrainedMethod(callable) || hasConstrainedParameters(callable)) {
            ExecutableDescriptor(
                kind = ExecutableDescriptor.Kind.Method,
                callable = callable,
                annotations = callable.annotations.filter { isConstraintAnnotation(it) },
                members = callable.parameters.let { parameters ->
                    parameters.filter { it.kind == KParameter.Kind.VALUE }.associate { parameter ->
                        parameter.name!! to buildPropertyDescriptor(
                            parameter.type.jvmErasure,
                            parameter.type,
                            parameter.annotations + (annotationMap?.get(parameter.name) ?: emptyList())
                        )
                    }
                }
            )
        } else null
    }

    private fun checkMethodValidationMethod(callable: KCallable<Any>): Boolean {
        if (callable.parameters.any { it.kind == KParameter.Kind.VALUE })
            throw ConstraintDeclarationException(
                "Methods annotated with @${angstromio.validation.MethodValidation::class.simpleName} must not declare any arguments"
            )
        if (callable.returnType != MethodValidationResult::class.createType())
            throw ConstraintDeclarationException("Methods annotated with @${angstromio.validation.MethodValidation::class.simpleName} must return a ${MethodValidationResult::class.simpleName}")
        return true
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

    private fun isConstraintAnnotation(annotation: Annotation): Boolean =
        constraintHelper.isConstraintAnnotation(annotation.annotationClass.java)

    // Array of annotations contains a constraint annotation
    private fun isConstrainedMethod(callable: KCallable<Any>): Boolean =
        callable.annotations.any { isConstraintAnnotation(it) }

    private fun hasConstrainedParameters(callable: KCallable<Any>): Boolean =
        callable.parameters.filter { it.kind == KParameter.Kind.VALUE }
            .any { it.annotations.any { ann -> isConstraintAnnotation(ann) } }

    // Array of annotation contains @MethodValidation
    private fun isMethodValidation(callable: KCallable<Any>): Boolean =
        callable.annotations.find<MethodValidation>() != null
}
package angstromio.validation.metadata

import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.jvm.javaConstructor
import kotlin.reflect.jvm.javaMethod

data class ExecutableDescriptor<R : Any>(
    val kind: ExecutableDescriptor.Kind,
    val callable: KFunction<R>,
    val annotations: List<Annotation>,
    val members: Map<String, PropertyDescriptor>
) : Descriptor {

    val declaringClass: Class<*> by lazy {
        when (kind) {
            is ExecutableDescriptor.Kind.Method ->
                callable.javaMethod!!.declaringClass

            is ExecutableDescriptor.Kind.Constructor ->
                callable.javaConstructor!!.declaringClass
        }
    }

    val declaringKClass: KClass<*> = declaringClass.kotlin


    sealed interface Kind {
        data object Constructor : Kind
        data object Method : Kind
    }
}
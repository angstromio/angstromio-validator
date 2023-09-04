package angstromio.validation.metadata

import java.lang.reflect.Type
import kotlin.reflect.KClass

data class DataClassDescriptor<T : Any>(
    val clazz: KClass<T>,
    val type: Type,
    val annotations: List<Annotation>,
    val constructors: List<ExecutableDescriptor<T>>,
    val members: Map<String, PropertyDescriptor>,
    val methods: List<ExecutableDescriptor<Any>>
) : Descriptor

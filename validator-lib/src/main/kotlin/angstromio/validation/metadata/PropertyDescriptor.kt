package angstromio.validation.metadata

import kotlin.reflect.KClass
import kotlin.reflect.KType

data class PropertyDescriptor(
    val clazz: KClass<out Any>,
    val kotlinType: KType,
    val cascadedType: KType?,
    val annotations: List<Annotation>,
    val isCascaded: Boolean = false
): Descriptor
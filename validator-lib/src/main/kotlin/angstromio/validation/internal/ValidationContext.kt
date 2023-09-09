package angstromio.validation.internal

import org.hibernate.validator.internal.engine.path.PathImpl

/** Utility class to carry necessary context for validation */
internal data class ValidationContext<T : Any>(
    val fieldName: String?,
    val rootClazz: Class<out T>?,
    val root: T?,
    val leaf: Any?,
    val path: PathImpl,
    val isFailFast: Boolean = false
)
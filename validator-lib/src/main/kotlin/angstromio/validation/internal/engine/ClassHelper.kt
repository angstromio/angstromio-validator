package angstromio.validation.internal.engine

internal object ClassHelper {

    private fun mkGetterName(name: String): String {
        return if (name.startsWith("get")) {
            name
        } else {
            "get${name.replaceFirstChar { it.uppercase() }}"
        }
    }

    fun unMaskMethodName(name: String): String {
        return if (name.startsWith("get") || name.startsWith("set")) {
            name.substring(3).replaceFirstChar { it.lowercase() }
        } else name
    }
    fun getFieldValue(instance: Any?, name: String): Any? {
        return when (instance) {
            null -> null
            else -> {
                val clazz = instance::class.java
                try {
                    val field = clazz.getField(name)
                    field.get(instance)
                } catch (e: Exception) {
                    // try method accessor
                    try {
                        val method = clazz.getDeclaredMethod(mkGetterName(name))
                        method.invoke(instance)
                    } catch (e: Exception) {
                        null
                    }
                }
            }
        }
    }
}
package angstromio.validation.internal

object Types {

    fun refineAsJavaType(clazz: Class<*>, instance: Any?): Class<out Any> {
        val name = clazz.name
        return when (name) {
            "byte" -> java.lang.Byte::class.java
            "short" -> java.lang.Short::class.java
            "int" -> java.lang.Integer::class.java
            "long" -> java.lang.Long::class.java
            "float" -> java.lang.Float::class.java
            "double" -> java.lang.Double::class.java
            "boolean" -> java.lang.Boolean::class.java
            "java.lang.Object" -> {  // need to use the instance type as we may be dealing with a generically typed class
                if (instance == null) {
                    clazz
                } else {
                    instance::class.java
                }
            }

            else -> clazz
        }
    }

    fun isFunction(clazz: Class<*>): Boolean = Function::class.java.isAssignableFrom(clazz)
}
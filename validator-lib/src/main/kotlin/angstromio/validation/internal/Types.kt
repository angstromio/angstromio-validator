package angstromio.validation.internal

import java.lang.reflect.Type
import kotlin.reflect.jvm.ExperimentalReflectionOnLambdas
import kotlin.reflect.jvm.jvmErasure
import kotlin.reflect.jvm.reflect

object Types {

    fun refineAsJavaType(type: Type): Type {
        val name = type.typeName
        return when (name) {
            "byte" -> java.lang.Byte::class.java
            "short" -> java.lang.Short::class.java
            "int" -> java.lang.Integer::class.java
            "long" -> java.lang.Long::class.java
            "float" -> java.lang.Float::class.java
            "double" -> java.lang.Double::class.java
            "boolean" -> java.lang.Boolean::class.java
            else -> type
        }
    }


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
                if (instance == null) { clazz } else {instance::class.java }
            }
            else -> clazz
        }
    }

    fun isFunction(clazz: Class<*>): Boolean = Function::class.java.isAssignableFrom(clazz)

    @OptIn(ExperimentalReflectionOnLambdas::class)
    fun getFunctionReturnType(instance: Any): Class<out Any> =
        (instance as Function<*>).reflect()!!.returnType.jvmErasure.java
}
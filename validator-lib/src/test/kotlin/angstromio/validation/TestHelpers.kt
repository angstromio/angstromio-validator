package angstromio.validation

import java.lang.reflect.Constructor
import java.lang.reflect.Method

object TestHelpers {

    fun getMethod(
        clazz: Class<*>,
        methodName: String,
        vararg parameterTypes: Class<*>
    ): Method = clazz.getDeclaredMethod(methodName, *parameterTypes)


    fun <T : Any> getConstructor(clazz: Class<T>, vararg parameterTypes: Class<*>): Constructor<T> =
        clazz.getDeclaredConstructor(*parameterTypes)
}
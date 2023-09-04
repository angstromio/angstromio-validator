@file:kotlin.jvm.JvmMultifileClass
@file:kotlin.jvm.JvmName("ConstraintViolationsKt")

package angstromio.validation.extensions

import angstromio.validation.internal.engine.ConstraintViolationFactory
import jakarta.validation.ConstraintViolation
import jakarta.validation.Payload
import org.hibernate.validator.engine.HibernateConstraintViolation

fun <T : Any> Collection<ConstraintViolation<T>>.sorted(): List<ConstraintViolation<T>> =
    ConstraintViolationFactory.sorted(this.toSet())

inline fun <reified P : Payload> ConstraintViolation<*>.getDynamicPayload(clazz: Class<P>): P? {
    return try {
        val hibernateConstraintViolation = this.unwrap(HibernateConstraintViolation::class.java)
        hibernateConstraintViolation.getDynamicPayload(clazz)
    } catch (e: Exception) {
        null
    }
}


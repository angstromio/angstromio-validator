package org.hibernate.validator.internal.properties.javabean

import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method

internal object JavaBeanFactory {

    fun newJavaBeanMethod(method: Method?): JavaBeanMethod =
        JavaBeanMethod(method)

    fun newJavaBeanConstructor(constructor: Constructor<*>?): JavaBeanConstructor =
        JavaBeanConstructor(constructor)

    fun newJavaBeanField(field: Field, resolvedPropertyName: String): JavaBeanField =
        JavaBeanField(field, resolvedPropertyName)
}
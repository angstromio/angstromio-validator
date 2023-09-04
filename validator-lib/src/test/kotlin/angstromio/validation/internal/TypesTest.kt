package angstromio.validation.internal

import angstromio.validation.testclasses
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.be
import io.kotest.matchers.should

class TypesTest : FunSpec ({

    test("Types#refineAsJavaType") {
        Types.refineAsJavaType(Byte::class.java, null) should be(Byte::class.javaObjectType)
        Types.refineAsJavaType(Short::class.java, null) should be(Short::class.javaObjectType)
        Types.refineAsJavaType(Long::class.java, null) should be(Long::class.javaObjectType)
        Types.refineAsJavaType(Float::class.java, null) should be(Float::class.javaObjectType)
        Types.refineAsJavaType(Double::class.java, null) should be(Double::class.javaObjectType)
        Types.refineAsJavaType(Boolean::class.java, null) should be(Boolean::class.javaObjectType)
        Types.refineAsJavaType(Int::class.java, null) should be(Int::class.javaObjectType)
        Types.refineAsJavaType(Any::class.java, null) should be(Any::class.javaObjectType)
        Types.refineAsJavaType(Any::class.java, testclasses.Users(listOf(testclasses.User("42", "J. Robinson", "M")))) should be(testclasses.Users::class.java)
    }
})
package angstromio.validation;

import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class ClassTest {

    // Shows the difference in annotations of a Kotlin defined class versus a Java define class
    @Test
    public void testAnnotations() throws NoSuchFieldException {
        Field[] fields = TestClasses.TestClass.class.getDeclaredFields();
        for (Field field : fields) {
            assertNotNull(field);
            AnnotatedParameterizedType apt = ((AnnotatedParameterizedType) field.getAnnotatedType());
            assertNotNull(apt);
            AnnotatedType[] ats = apt.getAnnotatedActualTypeArguments();
            assertNotNull(ats);
            assertTrue(ats.length > 0);
            for (AnnotatedType at : ats) {
                Annotation[] annotations = at.getAnnotations();
                // cannot read annotations
                assertEquals(0, annotations.length);
            }
        }

        fields = JavaTestClass.class.getDeclaredFields();
        for (Field field : fields) {
            assertNotNull(field);
            AnnotatedParameterizedType apt = ((AnnotatedParameterizedType) field.getAnnotatedType());
            assertNotNull(apt);
            AnnotatedType[] ats = apt.getAnnotatedActualTypeArguments();
            assertNotNull(ats);
            assertTrue(ats.length > 0);
            for (AnnotatedType at : ats) {
                Annotation[] annotations = at.getAnnotations();
                // can see the annotations
                assertTrue(annotations.length > 0);
            }
        }
    }
}

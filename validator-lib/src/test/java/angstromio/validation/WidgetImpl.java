package angstromio.validation;

import java.io.Serial;
import java.io.Serializable;
import java.lang.annotation.Annotation;

record WidgetImpl(String value) implements Widget, Serializable {

    public int hashCode() {
        // This is specified in java.lang.Annotation.
        return (127 * "value".hashCode()) ^ value.hashCode();
    }

    /**
     * Widget specific equals
     */
    public boolean equals(Object o) {
        if (!(o instanceof Widget other)) {
            return false;
        }

        return value.equals(other.value());
    }

    public String toString() {
        return "@" + Widget.class.getName() + "(value=" + value + ")";
    }

    public Class<? extends Annotation> annotationType() {
        return Widget.class;
    }

    @Serial
    private static final long serialVersionUID = 0;
}
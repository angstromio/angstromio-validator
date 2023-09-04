@file:kotlin.jvm.JvmMultifileClass
@file:kotlin.jvm.JvmName("PathsKt")

package angstromio.validation.extensions

import jakarta.validation.Path
import org.hibernate.validator.internal.engine.path.PathImpl

fun Path.getLeafNode(): Path.Node {
    return when (this) {
        is PathImpl ->
            this.leafNode
        else -> {
            var node = this.iterator().next()
            while (this.iterator().hasNext()) {
                node = this.iterator().next()
            }
            node
        }
    }
}

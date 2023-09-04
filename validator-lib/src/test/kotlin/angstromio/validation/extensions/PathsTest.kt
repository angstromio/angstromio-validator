package angstromio.validation.extensions

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.be
import io.kotest.matchers.nulls.beNull
import io.kotest.matchers.should
import io.kotest.matchers.shouldNot
import jakarta.validation.Path
import org.hibernate.validator.internal.engine.path.PathImpl
import org.junit.jupiter.api.assertThrows

class PathsTest : FunSpec({

    test("Paths#root") {
        val path: Path = PathImpl.createRootPath()

        val leafNode = path.getLeafNode()
        leafNode shouldNot beNull()
        leafNode.toString() should be("")
    }

    test("Paths#without leaf node") {
        val path = PathImpl.createRootPath()

        // only the root exists, so this fails
        assertThrows<IndexOutOfBoundsException> {
            PathImpl.createCopyWithoutLeafNode(path)
        }

        // add nodes
        path.addBeanNode()
        path.addPropertyNode("foo")

        val pathWithLeaf: Path = PathImpl.createCopy(path)
        val leafNode = pathWithLeaf.getLeafNode()
        leafNode shouldNot beNull()
        leafNode.toString() should be("foo")
    }

    test("Paths#propertyNode") {
        val path = PathImpl.createRootPath()
        path.addPropertyNode("leaf")

        val propertyPath: Path = PathImpl.createCopy(path)

        val leafNode = propertyPath.getLeafNode()
        leafNode shouldNot beNull()
        leafNode.toString() should be("leaf")
    }

    test("Paths#fromString") {
        val path: Path = PathImpl.createPathFromString("foo.bar.baz")

        val leafNode = path.getLeafNode()
        leafNode shouldNot beNull()
        leafNode.toString() should be("baz")
    }
})
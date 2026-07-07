package pl.iqtech.abyss.graph

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.SerializersModuleCollector
import pl.iqtech.abyss.dsl.serialName
import pl.iqtech.abyss.dsl.typeTag
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.NodeLike
import kotlin.reflect.KClass

// Bidirectional edge-type registry, populated eagerly (once, at worker construction) rather than
// lazily on first write — a lazy registry would reproduce the restart-tag-resolution gap TODO 1.20
// already fixed elsewhere: after a cold restart, a RemoveEdge/type-filtered query for a type that
// existed before restart but hasn't been re-added yet in the new process would fail to resolve,
// even though matching edges already exist in the durable store. @TypeTag/@SerialName are static,
// compile-time facts about registered classes, so walking the module once up front is safe.
class TypeTagRegistry private constructor(
    private val edgeTagByName: Map<String, Short>,
    private val edgeNameByTag: Map<Short, String>,
) {
    fun edgeTagOf(type: String): Short = edgeTagByName[type] ?: error("Edge type '$type' has no registered @TypeTag")
    fun edgeNameOf(tag: Short): String = edgeNameByTag[tag] ?: error("Unknown edge TypeTag $tag")

    companion object {
        fun of(module: SerializersModule): TypeTagRegistry {
            val collector = SubclassCollector()
            module.dumpTo(collector)

            // Two independent namespaces: a node tagged 5 and an edge tagged 5 never collide, since
            // they're compared in different fields and never against each other.
            val nodeTags = mutableSetOf<Short>()
            val edgeTags = mutableSetOf<Short>()
            val edgeTagByName = mutableMapOf<String, Short>()
            val edgeNameByTag = mutableMapOf<Short, String>()

            for ((base, actual) in collector.found) {
                when (base) {
                    NodeLike::class -> {
                        val tag = actual.typeTag()
                        require(nodeTags.add(tag)) { "Node TypeTag $tag already registered (class=$actual)" }
                    }
                    EdgeLike::class -> {
                        val tag = actual.typeTag()
                        require(edgeTags.add(tag)) { "Edge TypeTag $tag already registered (class=$actual)" }
                        val name = actual.serialName()
                        edgeTagByName[name] = tag
                        edgeNameByTag[tag] = name
                    }
                }
            }
            return TypeTagRegistry(edgeTagByName, edgeNameByTag)
        }
    }
}

private class SubclassCollector : SerializersModuleCollector {
    val found = mutableListOf<Pair<KClass<*>, KClass<*>>>()

    override fun <T : Any> contextual(kClass: KClass<T>, provider: (List<KSerializer<*>>) -> KSerializer<*>) {}

    override fun <Base : Any, Sub : Base> polymorphic(baseClass: KClass<Base>, actualClass: KClass<Sub>, actualSerializer: KSerializer<Sub>) {
        found += baseClass to actualClass
    }

    override fun <Base : Any> polymorphicDefaultSerializer(baseClass: KClass<Base>, serializerProvider: (Base) -> SerializationStrategy<Base>?) {}

    override fun <Base : Any> polymorphicDefaultDeserializer(baseClass: KClass<Base>, defaultDeserializerProvider: (String?) -> DeserializationStrategy<out Base>?) {}
}

package pl.iqtech.abyss.dsl

import kotlinx.serialization.SerialName
import pl.iqtech.abyss.store.api.TypeTag
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation

// An annotation on a KClass is a fixed, compile-time fact — it never changes at runtime, so
// re-running JVM reflection for it on every edge/node processed (as opposed to once per class,
// ever) buys nothing. Keyed on (KClass, annotation KClass) so distinct annotation types on the same
// class don't collide. ConcurrentHashMap disallows null values, so "no such annotation" (the common
// case for e.g. @EdgeConstraint) is cached as the NoAnnotation sentinel instead of null.
@PublishedApi
internal object NoAnnotation

@PublishedApi
internal val annotationCache = ConcurrentHashMap<Pair<KClass<*>, KClass<*>>, Any>()

@Suppress("UNCHECKED_CAST")
inline fun <reified A : Annotation> KClass<*>.cachedAnnotation(): A? =
    annotationCache.getOrPut(this to A::class) { findAnnotation<A>() ?: NoAnnotation }
        .takeIf { it !== NoAnnotation } as A?

// The ubiquitous "class -> its @SerialName string, or blow up" lookup, repeated at nearly every
// edge/node type-resolution call site.
fun KClass<*>.serialName(): String =
    cachedAnnotation<SerialName>()?.value ?: error("$this missing @SerialName")

fun KClass<*>.typeTag(): Short =
    cachedAnnotation<TypeTag>()?.value ?: error("$this missing @TypeTag")

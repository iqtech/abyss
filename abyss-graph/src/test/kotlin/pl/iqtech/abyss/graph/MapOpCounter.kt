package pl.iqtech.abyss.graph

import com.hazelcast.core.DistributedObject
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

// TODO 2.32 Phase 0: counts every IMap call made through [hz], per map name and method. In a single
// in-JVM member every partition is local, so a getAll is microseconds and wall-clock cannot price a
// route — the op count is the evidence. Pass [hz] when constructing the schema: the worker obtains its
// maps once, at construction. Seed through the real instance (or reset() after) so seeding isn't counted.
// Dynamic proxy, not `IMap by` delegation: complete by construction (no op slips through un-overridden)
// and immune to Hazelcast's @Nullable/@Nonnull override-signature traps.
// ponytail: blind to TransactionContext maps (newTransactionContext().getMap) — only
// HazelcastEphemeralStore uses them today; add a proxy there when an ephemeral route needs pricing.
class MapOpCounter(private val real: HazelcastInstance) {
    private val counts = ConcurrentHashMap<String, ConcurrentHashMap<String, AtomicLong>>()

    val hz: HazelcastInstance = proxy(real) { method, args, res ->
        if (method.name == "getMap") countingMap(args!![0] as String, res as IMap<*, *>) else res
    }

    // Besides the call count, "<op>.returned" sums what each call hands back — map entries / collection
    // elements, with an AdjacencyValue counted as its neighbour entries. Call count alone can't see a
    // type push-down: reading 17 edges of every type and 1 edge of one type are both a single getAll.
    private fun countingMap(name: String, map: IMap<*, *>): IMap<*, *> = proxy(map) { method, _, res ->
        if (method.declaringClass != Any::class.java && method.declaringClass != DistributedObject::class.java) {
            val ops = counts.computeIfAbsent(name) { ConcurrentHashMap() }
            ops.computeIfAbsent(method.name) { AtomicLong() }.incrementAndGet()
            val values = when (res) { is Map<*, *> -> res.values; is Collection<*> -> res; else -> null }
            if (values != null) ops.computeIfAbsent("${method.name}.returned") { AtomicLong() }
                .addAndGet(values.sumOf { v -> ((v as? Map.Entry<*, *>)?.value ?: v).let { (it as? AdjacencyValue)?.entries?.size ?: 1 }.toLong() })
        }
        res
    }

    fun count(map: String, op: String): Long = counts[map]?.get(op)?.get() ?: 0
    fun total(map: String): Long = counts[map]?.filterKeys { !it.endsWith(".returned") }?.values?.sumOf { it.get() } ?: 0
    fun snapshot(): Map<String, Map<String, Long>> = counts.mapValues { (_, ops) -> ops.mapValues { it.value.get() }.toSortedMap() }.toSortedMap()
    fun reset() = counts.clear()

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T : Any> proxy(target: T, crossinline after: (Method, Array<Any?>?, Any?) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            val res = try { method.invoke(target, *(args ?: emptyArray())) }
                      catch (e: InvocationTargetException) { throw e.targetException }   // callers see the real exception
            after(method, args, res)
        } as T
}

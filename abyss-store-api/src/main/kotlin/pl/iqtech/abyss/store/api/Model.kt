package pl.iqtech.abyss.store.api

import kotlin.time.Instant
import kotlinx.serialization.Polymorphic

@Polymorphic
interface NodeLike<ID> {
    val id: ID
    val tags: List<String>
    val createdAt: Instant
    val updatedAt: Instant
}

@Polymorphic
interface EdgeLike<ID> {
    val fromId: ID
    val toId: ID
    val tags: List<String>
    val createdAt: Instant
    val updatedAt: Instant
}

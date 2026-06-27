package pl.iqtech.abyss.store.api

import kotlinx.datetime.Instant
import kotlinx.serialization.Polymorphic
import kotlin.uuid.Uuid

@Polymorphic
interface NodeLike {
    val id: Uuid
    val tags: List<String>
    val createdAt: Instant
    val updatedAt: Instant
}

@Polymorphic
interface EdgeLike {
    val fromId: Uuid
    val toId: Uuid
    val tags: List<String>
    val createdAt: Instant
    val updatedAt: Instant
}

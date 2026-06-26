package pl.iqtech.abyss.store.api

import kotlinx.serialization.Contextual
import kotlinx.serialization.Polymorphic
import java.time.Instant
import java.util.UUID

@Polymorphic
interface NodeLike {
    @Contextual val id: UUID
    val tags: List<String>
    @Contextual val createdAt: Instant
    @Contextual val updatedAt: Instant
}

@Polymorphic
interface EdgeLike {
    @Contextual val fromId: UUID
    @Contextual val toId: UUID
    val tags: List<String>
    @Contextual val createdAt: Instant
    @Contextual val updatedAt: Instant
}

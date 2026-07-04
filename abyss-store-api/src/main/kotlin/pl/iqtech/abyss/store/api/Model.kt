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

// Edge serialization root. Endpoints may be different types (FID != TID) for cross-schema edges.
@Polymorphic
interface EdgeLike<FID, TID> {
    val fromId: FID
    val toId: TID
    val tags: List<String>
    val createdAt: Instant
    val updatedAt: Instant
}

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
interface RawEdgeLike<FID, TID> {
    val fromId: FID
    val toId: TID
    val tags: List<String>
    val createdAt: Instant
    val updatedAt: Instant
}

// Same-schema edge: both endpoints share one ID type. The 99% case (Road, Knows, …).
interface SchemaEdgeLike<ID> : RawEdgeLike<ID, ID>

// Cross-schema edge: endpoints belong to different (or not-statically-fixed) schemas.
interface CrossEdgeLike<FID, TID> : RawEdgeLike<FID, TID>

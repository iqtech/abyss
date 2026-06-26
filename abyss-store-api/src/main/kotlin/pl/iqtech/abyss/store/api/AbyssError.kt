package pl.iqtech.abyss.store.api

import java.util.UUID

sealed interface AbyssError {
    data class NodeNotFound(val id: UUID) : AbyssError
    data class EdgeNotFound(val fromId: UUID, val toId: UUID, val type: String) : AbyssError
    data class Unexpected(val cause: Throwable) : AbyssError
}

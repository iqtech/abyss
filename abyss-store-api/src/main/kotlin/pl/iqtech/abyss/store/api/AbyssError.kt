package pl.iqtech.abyss.store.api

import kotlin.uuid.Uuid

sealed interface AbyssError {
    data class NodeNotFound(val id: Uuid) : AbyssError
    data class EdgeNotFound(val fromId: Uuid, val toId: Uuid, val type: String) : AbyssError
    data class IntegrityError(val message: String) : AbyssError
    data class SchemaError(val message: String) : AbyssError
    data class Unexpected(val cause: Throwable) : AbyssError
}

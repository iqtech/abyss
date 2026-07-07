package pl.iqtech.abyss.store.api

sealed interface AbyssError {
    data class NodeNotFound(val id: Any) : AbyssError
    data class EdgeNotFound(val fromId: Any, val toId: Any, val type: String) : AbyssError
    data class IntegrityError(val message: String) : AbyssError
    data class SchemaError(val message: String) : AbyssError
    data class Unexpected(val cause: Throwable) : AbyssError
}

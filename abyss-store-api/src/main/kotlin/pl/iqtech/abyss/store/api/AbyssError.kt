package pl.iqtech.abyss.store.api

sealed interface AbyssError {
    data class NodeNotFound(val id: Any) : AbyssError
    data class EdgeNotFound(val fromId: Any, val toId: Any, val type: String) : AbyssError
    data class IntegrityError(val message: String) : AbyssError
    data class SchemaError(val message: String) : AbyssError
    data class Unexpected(val cause: Throwable) : AbyssError
    data class BatchPartiallyCommitted(val committedOps: Int, val cause: Throwable) : AbyssError
    data class ScanUnsupported(val reason: String) : AbyssError
    // A store read returned Left (TODO 1.38). Any Left fails the whole read, persistent or ephemeral
    // side, even when the other side answered: a store that can't answer isn't safe to read around.
    data class StoreUnavailable(val error: AbyssError) : AbyssError
}


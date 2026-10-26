package pl.iqtech.abyss.graph

import pl.iqtech.abyss.store.api.AbyssError

// TODO 1.38: carries AbyssError.StoreUnavailable out of read paths that can't return Either (Flows, the
// traversal engine). Every Either-returning entry point unwraps it back to Left via toAbyssError(). Lives in
// abyss-graph, not the store API: stores return Left, they never throw this.
class StoreUnavailableException(val error: AbyssError.StoreUnavailable) :
    RuntimeException("Store unavailable: ${error.error}", (error.error as? AbyssError.Unexpected)?.cause)

internal fun Throwable.toAbyssError(): AbyssError = (this as? StoreUnavailableException)?.error ?: AbyssError.Unexpected(this)

internal fun AbyssError.unavailable(): Nothing = throw StoreUnavailableException(AbyssError.StoreUnavailable(this))

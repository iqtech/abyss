package pl.iqtech.abyss.graph.serialization

import kotlinx.serialization.json.Json
import pl.iqtech.abyss.store.api.abyssSerializersModule

val customJsonSerializer = Json {
    isLenient = true
    explicitNulls = false
    encodeDefaults = true
    coerceInputValues = true
    serializersModule = abyssSerializersModule
}

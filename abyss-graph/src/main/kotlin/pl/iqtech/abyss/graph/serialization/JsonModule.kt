package pl.iqtech.abyss.graph.serialization

import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual

// Created by cane, 4/7/25 9:18 pm

val customJsonModule = SerializersModule {
    contextual(UuidSerializer)
    contextual(InstantSerializer)
}

val customJsonSerializer = Json {
    prettyPrint = true
    isLenient = true
    explicitNulls = false
    encodeDefaults = true
    coerceInputValues = true
    serializersModule = customJsonModule
}

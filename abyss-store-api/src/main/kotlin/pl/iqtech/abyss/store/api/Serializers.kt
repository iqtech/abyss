package pl.iqtech.abyss.store.api

import kotlinx.serialization.modules.EmptySerializersModule

// kotlin.uuid.Uuid and kotlinx.datetime.Instant both have built-in kotlinx.serialization support
// (UUID string and ISO-8601 string respectively) — no custom serializers needed.
val abyssSerializersModule = EmptySerializersModule()

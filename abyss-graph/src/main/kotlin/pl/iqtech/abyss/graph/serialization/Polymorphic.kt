package pl.iqtech.abyss.graph.serialization

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.plus
import kotlinx.serialization.modules.polymorphic

// Created by cane, 24/4/26


/**
 * A deserializer for polymorphic types that handles unknown types by invoking the [onUnknown] callback.
 *
 * This deserializer is useful when you want to handle unexpected or new types in a polymorphic hierarchy
 * by providing a default object instead of throwing an exception.
 *
 * @param U The base type of the polymorphic hierarchy.
 * @property onUnknown A lambda that takes the [JsonElement] of the unknown type and returns an instance of [U].
 */
class PolymorphicFallbackSerializer<U : Any>(private val onUnknown: (JsonElement) -> U) : KSerializer<U> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun deserialize(decoder: Decoder): U {
        return onUnknown((decoder as JsonDecoder).decodeJsonElement())
    }

    override fun serialize(encoder: Encoder, value: U) {
        if (encoder is JsonEncoder) {
            encoder.encodeJsonElement(Json.parseToJsonElement(value.toString()))
        } else {
            encoder.encodeString(value.toString())
        }
    }
}

/**
 * Creates a new [Json] instance that handles unknown polymorphic types of class [T].
 *
 * @param T The base class for polymorphic serialization.
 * @param baseJsonSerializer The base [Json] instance to inherit configuration from. Defaults to [customJsonSerializer].
 * @param onUnknown A callback used when an unknown subclass of [T] is encountered during deserialization.
 * @return A [Json] instance configured with a default deserializer for unknown types of [T].
 */
inline fun <reified T : Any> createPolymorphicJsonSerializer(
    baseJsonSerializer: Json = customJsonSerializer,
    noinline onUnknown: (JsonElement) -> T
) = Json(from = baseJsonSerializer) {
    serializersModule = baseJsonSerializer.serializersModule + SerializersModule {
        polymorphic(T::class) {
            defaultDeserializer { PolymorphicFallbackSerializer(onUnknown) }
        }
    }
}
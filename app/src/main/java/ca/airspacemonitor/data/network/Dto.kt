package ca.airspacemonitor.data.network

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * alt_baro in the tar1090/readsb v2 schema is a number, the string "ground",
 * or absent. We accept any JSON value and keep only numeric ones.
 */
object FlexibleAltitudeSerializer : KSerializer<Double?> {

    private val delegate = JsonElement.serializer()
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun deserialize(decoder: Decoder): Double? {
        val element = decoder.decodeSerializableValue(delegate)
        val primitive = element as? JsonPrimitive ?: return null
        // "ground" and other non-numeric strings fail to parse -> null.
        return primitive.content.toDoubleOrNull()
    }

    override fun serialize(encoder: Encoder, value: Double?) {
        val element = value?.let { JsonPrimitive(it) } ?: JsonNull
        encoder.encodeSerializableValue(delegate, element)
    }
}

@Serializable
data class V2Response(
    val now: Double? = null,
    val ac: List<AircraftDto>? = null,
)

@Serializable
data class AircraftDto(
    val hex: String = "",
    val flight: String? = null,
    val r: String? = null,
    val t: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    @Serializable(with = FlexibleAltitudeSerializer::class) val alt_baro: Double? = null,
    val alt_geom: Double? = null,
    val gs: Double? = null,
    val track: Double? = null,
    val seen: Double? = null,
    /** Seconds since the last position update reached the aggregator. */
    val seen_pos: Double? = null,
    val rssi: Double? = null,
    /** tar1090 may emit [] or a map of mlat-derived fields; only presence matters. */
    val mlat: JsonElement? = null,
    val dbFlags: Long? = null,
) {
    val hasMlat: Boolean
        get() = mlat != null && mlat !is JsonNull &&
            ((mlat as? kotlinx.serialization.json.JsonArray)?.isNotEmpty() == true ||
                mlat is kotlinx.serialization.json.JsonObject)
}

@Serializable
data class ElevationResponse(val elevation: List<Double> = emptyList())
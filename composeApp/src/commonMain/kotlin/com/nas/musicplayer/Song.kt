package com.nas.musicplayer

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

// 서버의 0/1 또는 Boolean 값을 모두 처리하는 Serializer
object FlexibleBooleanSerializer : KSerializer<Boolean> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("FlexibleBoolean", PrimitiveKind.BOOLEAN)

    override fun deserialize(decoder: Decoder): Boolean {
        return if (decoder is JsonDecoder) {
            val element = decoder.decodeJsonElement()
            if (element is JsonPrimitive) {
                if (element.isString) {
                    element.content.lowercase() == "true" || element.content == "1"
                } else {
                    element.booleanOrNull ?: (element.intOrNull != 0)
                }
            } else false
        } else {
            try { decoder.decodeBoolean() } catch (e: Exception) { false }
        }
    }

    override fun serialize(encoder: Encoder, value: Boolean) = encoder.encodeBoolean(value)
}

@Serializable
@Entity(tableName = "playlist_table")
data class Song(
    @PrimaryKey(autoGenerate = true)
    val dbId: Int = 0,
    @SerialName("id") val id: Long = 0L,
    @SerialName("name") val name: String? = null,
    @SerialName("path") val path: String? = null,
    @Serializable(with = FlexibleBooleanSerializer::class)
    @SerialName("is_dir") val isDir: Boolean = false,
    @SerialName("size") val size: Long = 0L,
    @SerialName("category") val category: String? = null,
    @SerialName("stream_url") val streamUrl: String? = null,
    @SerialName("mtime") val mtime: String? = null,
    @SerialName("parent_path") val parentPath: String? = null,
    @SerialName("mtime_ts") val mtimeTs: Double? = null,
    @SerialName("meta_id") val metaId: String? = null,
    @SerialName("meta_poster") val metaPoster: String? = null,
    val artist: String = "Unknown Artist",
    val albumName: String = "Unknown Album",
    val albumArtRes: Int? = null,
    val albumInfo: String? = null,
    val lyrics: String? = null,
    @SerialName("genre") val genre: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("album_artist") val albumArtist: String? = null
)

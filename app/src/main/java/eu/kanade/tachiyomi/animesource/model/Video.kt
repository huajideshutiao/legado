// Copyright The Aniyomi Contributors. Apache-2.0.
package eu.kanade.tachiyomi.animesource.model

import android.net.Uri
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.Headers
import kotlin.concurrent.Volatile

/**
 * A sub/dub track.
 */
@Serializable
data class Track(val url: String, val lang: String)

enum class ChapterType {
    Opening,
    Ending,
    Recap,
    MixedOp,
    Other,
}

/**
 * A class defining a timestamp. Displayed as video chapters in the app.
 *
 * @param start Start of the timestamp, in seconds.
 * @param end End of the timestamp, in seconds.
 * @param name Display name of timestamp.
 * @param type Type of timestamp.
 */
@Serializable
data class TimeStamp(
    val start: Double,
    val end: Double,
    val name: String,
    val type: ChapterType = ChapterType.Other,
)

/**
 * The instance that contains the data needed to watch a video.
 *
 * v14~v17 三代构造器并存的宿主兼容实现 (逐字对齐 aniyomi master source-api):
 * - v17 主形状 (15 参含 memo);
 * - v14 旧主形状经次构造器落地, url/quality 以只读兼容属性承接;
 * - v16 主形状 (14 参无 memo) 以 HIDDEN 次构造器 + copy() 重载承接,
 *   保证 lib16 扩展字节码对主构造器/copy 的链接不破。
 */
data class Video(
    var videoUrl: String = "",
    val videoTitle: String = "",
    val resolution: Int? = null,
    val bitrate: Int? = null,
    val headers: Headers? = null,
    val preferred: Boolean = false,
    val subtitleTracks: List<Track> = emptyList(),
    val audioTracks: List<Track> = emptyList(),
    val timestamps: List<TimeStamp> = emptyList(),
    val mpvArgs: List<Pair<String, String>> = emptyList(),
    val ffmpegStreamArgs: List<Pair<String, String>> = emptyList(),
    val ffmpegVideoArgs: List<Pair<String, String>> = emptyList(),
    val internalData: String = "",
    val initialized: Boolean = false,
    val memo: JsonObject = JsonObject(emptyMap()),
) {
    // TODO(1.6): Remove after ext lib bump
    @Deprecated("Use videoTitle instead", ReplaceWith("videoTitle"))
    val quality: String
        get() = videoTitle

    // TODO(1.6): Remove after ext lib bump
    val url: String
        get() = videoPageUrl

    // TODO(1.6): Remove after ext lib bump
    private var videoPageUrl: String = ""

    // TODO(1.6): Remove after ext lib bump
    constructor(
        url: String,
        quality: String,
        videoUrl: String?,
        headers: Headers? = null,
        subtitleTracks: List<Track> = emptyList(),
        audioTracks: List<Track> = emptyList(),
    ) : this(
        videoTitle = quality,
        videoUrl = videoUrl ?: "null",
        headers = headers,
        subtitleTracks = subtitleTracks,
        audioTracks = audioTracks,
    ) {
        this.videoPageUrl = url
    }

    // TODO(1.6): Remove after ext lib bump
    @Suppress("UNUSED_PARAMETER")
    constructor(
        url: String,
        quality: String,
        videoUrl: String?,
        uri: Uri? = null,
        headers: Headers? = null,
    ) : this(url, quality, videoUrl, headers)

    // Ext lib 16 constructor
    @Deprecated("Used only for compatibility with ext lib 16, do not use", level = DeprecationLevel.HIDDEN)
    constructor(
        videoUrl: String = "",
        videoTitle: String = "",
        resolution: Int? = null,
        bitrate: Int? = null,
        headers: Headers? = null,
        preferred: Boolean = false,
        subtitleTracks: List<Track> = emptyList(),
        audioTracks: List<Track> = emptyList(),
        timestamps: List<TimeStamp> = emptyList(),
        mpvArgs: List<Pair<String, String>> = emptyList(),
        ffmpegStreamArgs: List<Pair<String, String>> = emptyList(),
        ffmpegVideoArgs: List<Pair<String, String>> = emptyList(),
        internalData: String = "",
        initialized: Boolean = false,
    ) : this(
        videoUrl, videoTitle, resolution, bitrate, headers, preferred, subtitleTracks, audioTracks, timestamps, mpvArgs,
        ffmpegStreamArgs, ffmpegVideoArgs, internalData, initialized, JsonObject(emptyMap()),
    )

    // Ext lib 16 copy video
    @Deprecated("Used only for compatibility with ext lib 16, do not use", level = DeprecationLevel.HIDDEN)
    fun copy(
        videoUrl: String = this.videoUrl,
        videoTitle: String = this.videoTitle,
        resolution: Int? = this.resolution,
        bitrate: Int? = this.bitrate,
        headers: Headers? = this.headers,
        preferred: Boolean = this.preferred,
        subtitleTracks: List<Track> = this.subtitleTracks,
        audioTracks: List<Track> = this.audioTracks,
        timestamps: List<TimeStamp> = this.timestamps,
        mpvArgs: List<Pair<String, String>> = this.mpvArgs,
        ffmpegStreamArgs: List<Pair<String, String>> = this.ffmpegStreamArgs,
        ffmpegVideoArgs: List<Pair<String, String>> = this.ffmpegVideoArgs,
        internalData: String = this.internalData,
        initialized: Boolean = this.initialized,
    ): Video = Video(
        videoUrl = videoUrl,
        videoTitle = videoTitle,
        resolution = resolution,
        bitrate = bitrate,
        headers = headers,
        preferred = preferred,
        subtitleTracks = subtitleTracks,
        audioTracks = audioTracks,
        timestamps = timestamps,
        mpvArgs = mpvArgs,
        ffmpegStreamArgs = ffmpegStreamArgs,
        ffmpegVideoArgs = ffmpegVideoArgs,
        internalData = internalData,
        initialized = initialized,
        memo = JsonObject(emptyMap()),
    )

    @Transient
    @Volatile
    var status: State = State.QUEUE
        set(value) {
            field = value
        }

    fun usesHttpServer(): Boolean {
        if (localUrl.find(videoUrl) != null) {
            return true
        }

        if (audioTracks.any { localUrl.find(it.url) != null }) {
            return true
        }

        if (subtitleTracks.any { localUrl.find(it.url) != null }) {
            return true
        }

        return false
    }

    fun copyHttpServer(port: Int): Video {
        val newHost = "http://localhost:$port"
        return this.copy(
            videoUrl = localUrl.replace(videoUrl, newHost),
            subtitleTracks = subtitleTracks.map {
                it.copy(url = localUrl.replace(it.url, newHost))
            },
            audioTracks = audioTracks.map {
                it.copy(url = localUrl.replace(it.url, newHost))
            },
        )
    }

    enum class State {
        QUEUE,
        LOAD_VIDEO,
        READY,
        ERROR,
    }

    companion object {
        const val MPV_ARGS_TAG = "ANIYOMI_MPV_ARGS"
        private val localUrl = Regex("""http:\/\/localhost:1(?!\d)""")
    }
}

@Serializable
data class SerializableVideo(
    val videoUrl: String = "",
    val videoTitle: String = "",
    val resolution: Int? = null,
    val bitrate: Int? = null,
    val headers: List<Pair<String, String>>? = null,
    val preferred: Boolean = false,
    val subtitleTracks: List<Track> = emptyList(),
    val audioTracks: List<Track> = emptyList(),
    val timestamps: List<TimeStamp> = emptyList(),
    val mpvArgs: List<Pair<String, String>> = emptyList(),
    val ffmpegStreamArgs: List<Pair<String, String>> = emptyList(),
    val ffmpegVideoArgs: List<Pair<String, String>> = emptyList(),
    val internalData: String = "",
    val initialized: Boolean = false,
    val memo: JsonObject = JsonObject(emptyMap()),
) {

    companion object {
        fun List<Video>.serialize(): String =
            Json.encodeToString(
                this.map { vid ->
                    SerializableVideo(
                        vid.videoUrl,
                        vid.videoTitle,
                        vid.resolution,
                        vid.bitrate,
                        vid.headers?.toList(),
                        vid.preferred,
                        vid.subtitleTracks,
                        vid.audioTracks,
                        vid.timestamps,
                        vid.mpvArgs,
                        vid.ffmpegStreamArgs,
                        vid.ffmpegVideoArgs,
                        vid.internalData,
                        vid.initialized,
                        vid.memo,
                    )
                },
            )

        fun String.toVideoList(): List<Video> =
            Json.decodeFromString<List<SerializableVideo>>(this)
                .map { sVid ->
                    Video(
                        sVid.videoUrl,
                        sVid.videoTitle,
                        sVid.resolution,
                        sVid.bitrate,
                        sVid.headers
                            ?.flatMap { it.toList() }
                            ?.let { Headers.headersOf(*it.toTypedArray()) },
                        sVid.preferred,
                        sVid.subtitleTracks,
                        sVid.audioTracks,
                        sVid.timestamps,
                        sVid.mpvArgs,
                        sVid.ffmpegStreamArgs,
                        sVid.ffmpegVideoArgs,
                        sVid.internalData,
                        sVid.initialized,
                        sVid.memo,
                    )
                }
    }
}

package com.github.libretube.api.obj

import com.github.libretube.db.obj.DownloadItem
import com.github.libretube.enums.FileType
import com.github.libretube.helpers.ProxyHelper
import com.github.libretube.json.SafeInstantSerializer
import com.github.libretube.parcelable.DownloadData
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.io.path.Path

@Serializable
data class Streams(
    var title: String,
    val description: String,

    @Serializable(SafeInstantSerializer::class)
    @SerialName("uploadDate")
    val uploadTimestamp: Instant?,
    val uploaded: Long? = null,

    val uploader: String,
    val uploaderUrl: String,
    val uploaderAvatar: String? = null,
    var thumbnailUrl: String,
    val category: String,
    val license: String = "YouTube licence",
    val visibility: String = "public",
    val tags: List<String> = emptyList(),
    val metaInfo: List<MetaInfo> = emptyList(),
    val hls: String? = null,
    val dash: String? = null,
    val lbryId: String? = null,
    val uploaderVerified: Boolean,
    val duration: Long,
    val views: Long = 0,
    val likes: Long = 0,
    val dislikes: Long = 0,
    val audioStreams: List<PipedStream> = emptyList(),
    val videoStreams: List<PipedStream> = emptyList(),
    var relatedStreams: List<StreamItem> = emptyList(),
    val subtitles: List<Subtitle> = emptyList(),
    val livestream: Boolean = false,
    val proxyUrl: String? = null,
    val chapters: List<ChapterSegment> = emptyList(),
    val uploaderSubscriberCount: Long = 0,
    val previewFrames: List<PreviewFrames> = emptyList()
) {
    fun toDownloadItems(downloadData: DownloadData): List<DownloadItem> {
        val (id, name, videoFormat, videoQuality, audioFormat, audioQuality, audioTrackLocale, subCode) = downloadData
        val items = mutableListOf<DownloadItem>()

        if (!videoQuality.isNullOrEmpty() && !videoFormat.isNullOrEmpty()) {
            val stream = videoStreams.find {
                it.quality == videoQuality && it.format == videoFormat
            }
            stream?.toDownloadItem(FileType.VIDEO, id, name)?.let { items.add(it) }
        }

        if (!audioQuality.isNullOrEmpty() && !audioFormat.isNullOrEmpty()) {
            val stream = audioStreams.find {
                it.quality == audioQuality && it.format == audioFormat && it.audioTrackLocale == audioTrackLocale
            }
            stream?.toDownloadItem(FileType.AUDIO, id, name)?.let { items.add(it) }
        }

        if (!subCode.isNullOrEmpty()) {
            items.add(
                DownloadItem(
                    type = FileType.SUBTITLE,
                    videoId = id,
                    fileName = "${name}_$subCode.srt",
                    path = Path(""),
                    url = subtitles.find {
                        it.code == subCode
                    }?.url?.let { ProxyHelper.unwrapUrl(it) }
                )
            )
        }

        return items
    }

    fun toStreamItem(videoId: String): StreamItem {
        return StreamItem(
            url = videoId,
            title = title,
            thumbnail = thumbnailUrl,
            uploaderName = uploader,
            uploaderUrl = uploaderUrl,
            uploaderAvatar = uploaderAvatar,
            uploadedDate = uploadTimestamp?.toLocalDateTime(TimeZone.currentSystemDefault())?.date
                ?.toString(),
            uploaded = uploaded ?: uploadTimestamp?.toEpochMilliseconds() ?: 0,
            duration = duration,
            views = views,
            uploaderVerified = uploaderVerified,
            shortDescription = description
        )
    }

    companion object {
        const val categoryMusic = "Music"
    }
}

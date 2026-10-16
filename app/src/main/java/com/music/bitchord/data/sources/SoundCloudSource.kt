package com.music.bitchord.data.sources

import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import java.util.Locale

/**
 * SoundCloud's native NewPipeExtractor adapter.
 *
 * Track IDs are canonical SoundCloud URLs, wrapped by SourceRegistry when they
 * enter the queue. We keep the URL rather than a transient API ID because the
 * extractor accepts it directly for playback and it remains stable across
 * process restarts.
 */
class SoundCloudSource(
    override val config: SourceConfig,
) : MusicSource, SourceRegistry.ConfigBacked {
    override val configId: String get() = config.id
    override val kind: SourceKind get() = SourceKind.SOUNDCLOUD
    override val displayName: String get() = config.label.ifBlank { SourceKind.SOUNDCLOUD.label }

    override suspend fun health(): SourceHealth = SourceHealth.Ok()

    override suspend fun search(
        query: String,
        limit: Int,
        waitForAll: Boolean,
        request: StreamRequest?,
    ): List<Song> = withContext(Dispatchers.IO) {
        val term = query.trim()
        if (term.isEmpty() || limit <= 0) return@withContext emptyList()
        StreamResolver.ensureNewPipeInitialized()
        val service = ServiceList.SoundCloud
        val handler = service.getSearchQHFactory().fromQuery(term, listOf("tracks"), "")
        val extractor = service.getSearchExtractor(handler)
        extractor.fetchPage()
        extractor.initialPage.items
            .filterIsInstance<StreamInfoItem>()
            .asSequence()
            .filter { it.url.isNotBlank() && it.name.isNotBlank() }
            .take(limit)
            .map { item ->
                val seconds = item.duration.takeIf { it > 0L }
                Song(
                    videoId = SourceRegistry.trackKey(config.id, item.url),
                    title = item.name,
                    artist = item.uploaderName.orEmpty(),
                    thumbnailUrl = item.thumbnailUrl,
                    durationText = seconds?.let { String.format(Locale.ROOT, "%d:%02d", it / 60, it % 60) },
                    sourceQuality = "SOUNDCLOUD",
                )
            }
            .toList()
    }

    override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? =
        withContext(Dispatchers.IO) {
            if (!trackId.startsWith("https://soundcloud.com/") &&
                !trackId.startsWith("http://soundcloud.com/")
            ) return@withContext null
            StreamResolver.ensureNewPipeInitialized()
            val extractor = ServiceList.SoundCloud.getStreamExtractor(trackId)
            extractor.fetchPage()
            val candidates = extractor.audioStreams.filter {
                !it.content.isNullOrBlank() &&
                    it.deliveryMethod in setOf(
                        DeliveryMethod.PROGRESSIVE_HTTP,
                        DeliveryMethod.HLS,
                    )
            }
            if (candidates.isEmpty()) return@withContext null
            val selected = when (request) {
                is StreamRequest.Capped -> candidates
                    .filter { it.averageBitrate in 1..request.maxKbps }
                    .maxByOrNull { it.averageBitrate }
                StreamRequest.Best, StreamRequest.Lossless -> candidates.maxByOrNull { it.averageBitrate }
            } ?: return@withContext null
            SourceStream(
                url = selected.content,
                format = StreamFormat(
                    codec = selected.format?.suffix?.lowercase(Locale.ROOT),
                    kbps = selected.averageBitrate.takeIf { it > 0 },
                ),
            )
        }
}

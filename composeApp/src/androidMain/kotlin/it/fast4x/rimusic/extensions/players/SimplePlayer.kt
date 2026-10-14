package it.fast4x.rimusic.extensions.players

import androidx.annotation.OptIn
import androidx.core.content.edit
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import it.fast4x.environment.Environment
import it.fast4x.environment.EnvironmentExt
import it.fast4x.environment.models.Context
import it.fast4x.environment.models.Context.Companion.DefaultWeb3
import it.fast4x.environment.models.Context.Companion.VISIONOS
import it.fast4x.environment.models.PlayerResponse
import it.fast4x.environment.utils.NewPipeUtils
import it.fast4x.rimusic.appContext
import it.fast4x.rimusic.enums.AudioQualityFormat
import it.fast4x.rimusic.isConnectionMetered
import it.fast4x.rimusic.isConnectionMeteredEnabled
import it.fast4x.rimusic.models.Format
import it.fast4x.rimusic.utils.preferences
import it.fast4x.rimusic.utils.ytVisitorDataKey
import okhttp3.OkHttpClient
import timber.log.Timber

object SimplePlayer {
    private val httpClient = OkHttpClient.Builder()
        .proxy(Environment.proxy)
        .build()

    /**
     * The main client is used for metadata and initial streams.
     * Do not use other clients for this because it can result in inconsistent metadata.
     * For example other clients can have different normalization targets (loudnessDb).
     *
     * should be preferred here because currently it is the only client which provides:
     * - the correct metadata (like loudnessDb)
     * - premium formats
     */
    private val MAIN_CLIENT: Context.Client = Context.DefaultWeb.client

    /**
     * Clients used for fallback streams in case the streams of the main client do not work.
     */
    private val STREAM_FALLBACK_CLIENTS: Array<Context.Client> = arrayOf(
        // TVHTML5_SIMPLY_EMBEDDED_PLAYER was retired by YouTube ("no longer supported").
        // IOS (DefaultWeb3) without PoToken only serves the first ~1MB, so keep it last.
        VISIONOS.client,
        DefaultWeb3.client,
    )

    data class PlaybackData(
        val audioConfig: PlayerResponse.PlayerConfig.AudioConfig?,
        val videoDetails: PlayerResponse.VideoDetails?,
        val playbackTracking: PlayerResponse.PlaybackTracking?,
        val format: PlayerResponse.StreamingData.Format,
        val streamUrl: String,
        val streamExpiresInSeconds: Int,
    )

    /**
     * Custom player response intended to use for playback.
     * Metadata like audioConfig and videoDetails are from [MAIN_CLIENT].
     * Format & stream can be from [MAIN_CLIENT] or [STREAM_FALLBACK_CLIENTS].
     */
    @OptIn(UnstableApi::class)
    suspend fun playerResponseForPlayback(
        videoId: String,
        playlistId: String? = null,
        playedFormat: Format?,
        audioQuality: AudioQualityFormat,
        //connectivityManager: ConnectivityManager,
    ): Result<PlaybackData> = runCatching {
        /**
         * This is required for some clients to get working streams however
         * it should not be forced for the [MAIN_CLIENT] because the response of the [MAIN_CLIENT]
         * is required even if the streams won't work from this client.
         * This is why it is allowed to be null.
         */
        val signatureTimestamp = getSignatureTimestampOrNull(videoId)
        var visitorDataRefreshed = false
        if (Environment.visitorData.isBlank() || Environment.visitorData == Environment._uMYwa66ycM) {
            // the bundled default visitorData is stale and gets "confirm you're not a bot"
            visitorDataRefreshed = refreshVisitorData()
        }
        val mainPlayerResponse =
            EnvironmentExt.simplePlayer(videoId, playlistId, MAIN_CLIENT, signatureTimestamp).getOrThrow()
        val audioConfig = mainPlayerResponse.playerConfig?.audioConfig
        val videoDetails = mainPlayerResponse.videoDetails
        val playbackTracking = mainPlayerResponse.playbackTracking
        var format: PlayerResponse.StreamingData.Format? = null
        var streamUrl: String? = null
        var streamExpiresInSeconds: Int? = null
        var streamPlayerResponse: PlayerResponse? = null
        for (clientIndex in (-1 until STREAM_FALLBACK_CLIENTS.size)) {
            // reset for each client
            format = null
            streamUrl = null
            streamExpiresInSeconds = null
            // decide which client to use
            if (clientIndex == -1) {
                // try with streams from main client first
                streamPlayerResponse = mainPlayerResponse
            } else {
                // after main client use fallback clients
                val client = STREAM_FALLBACK_CLIENTS[clientIndex]
                if (client.loginRequired && Environment.cookie == null) {
                    // skip client if it requires login but user is not logged in
                    continue
                }
                streamPlayerResponse =
                    EnvironmentExt.simplePlayer(videoId, playlistId, client, signatureTimestamp).getOrNull()
                if (streamPlayerResponse?.playabilityStatus?.status == "LOGIN_REQUIRED" && !visitorDataRefreshed) {
                    // saved visitorData expired: get a new one and retry this client once
                    visitorDataRefreshed = true
                    if (refreshVisitorData())
                        streamPlayerResponse =
                            EnvironmentExt.simplePlayer(videoId, playlistId, client, signatureTimestamp).getOrNull()
                }
            }
            // process current client response
            if (streamPlayerResponse?.playabilityStatus?.status == "OK") {
                format =
                    findFormat(
                        streamPlayerResponse,
                        playedFormat,
                        audioQuality,
                        //connectivityManager,
                    ) ?: continue
                streamUrl = findUrlOrNull(format, videoId) ?: continue
                streamExpiresInSeconds =
                    streamPlayerResponse.streamingData?.expiresInSeconds ?: continue
                if (clientIndex == STREAM_FALLBACK_CLIENTS.size - 1) {
                    /** skip [validateStatus] for last client */
                    break
                }
                if (validateStatus(streamUrl, format.contentLength)) {
                    // working stream found
                    break
                }
            }
        }
        if (streamPlayerResponse == null) {
            throw Exception("Bad stream player response")
        }
        if (streamPlayerResponse.playabilityStatus?.status != "OK") {
            throw PlaybackException(
                streamPlayerResponse.playabilityStatus?.reason,
                null,
                PlaybackException.ERROR_CODE_REMOTE_ERROR
            )
        }
        if (streamExpiresInSeconds == null) {
            throw Exception("Missing stream expire time")
        }
        if (format == null) {
            throw Exception("Could not find format")
        }
        if (streamUrl == null) {
            throw Exception("Could not find stream url")
        }
        PlaybackData(
            audioConfig,
            videoDetails,
            playbackTracking,
            format,
            streamUrl,
            streamExpiresInSeconds,
        )
    }

    /**
     * Simple player response intended to use for metadata only.
     * Stream URLs of this response might not work so don't use them.
     */
    suspend fun playerResponseForMetadata(
        videoId: String,
        playlistId: String? = null,
    ): Result<PlayerResponse> =
        EnvironmentExt.simplePlayer(videoId, playlistId, client = MAIN_CLIENT)

    private fun findFormat(
        playerResponse: PlayerResponse,
        playedFormat: Format?,
        audioQuality: AudioQualityFormat,
        //connectivityManager: ConnectivityManager,
    ): PlayerResponse.StreamingData.Format? =
        if (playedFormat != null) {
            playerResponse.streamingData?.adaptiveFormats?.find { it.itag == playedFormat.itag }
        } else {
            playerResponse.streamingData?.adaptiveFormats
                ?.filter { it.isAudio }
                ?.maxByOrNull {
                    it.bitrate *
                            when (audioQuality) {
                                AudioQualityFormat.Auto -> if (isConnectionMeteredEnabled() && isConnectionMetered()) -1 else 2
                                AudioQualityFormat.High -> 1
                                AudioQualityFormat.Medium -> -1
                                AudioQualityFormat.Low -> -2
                            } + (if (it.mimeType.startsWith("audio/webm")) 10240 else 0) // prefer opus stream
                }
        }

    /**
     * Checks if the stream url returns a successful status.
     * If this returns true the url is likely to work.
     * If this returns false the url might cause an error during playback.
     */
    private fun validateStatus(url: String, contentLength: Long?): Boolean {
        try {
            // A HEAD (or the first bytes) always succeeds, but streams without a PoToken
            // are cut after ~1MB and then return 403. Probe a range from the middle of the
            // file instead, so capped streams are rejected and the next client is tried.
            val probeStart = contentLength?.takeIf { it > 2_000_000 }?.let { it / 2 }
                ?: contentLength?.let { (it - 2_000).coerceAtLeast(0) }
            val requestBuilder = okhttp3.Request.Builder().url(url)
            if (probeStart == null) requestBuilder.head()
            else requestBuilder.header("Range", "bytes=$probeStart-${probeStart + 1_023}")
            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                return response.isSuccessful
            }
        } catch (e: Exception) {
            Timber.e("SimplePlayer Could not validate stream url: $url")
            println("SimplePlayer Could not validate stream url: $url")
        }
        return false
    }

    /**
     * Fetches a fresh visitorData, uses it for the next requests and saves it.
     * Returns true if a new value was obtained.
     */
    private suspend fun refreshVisitorData(): Boolean {
        val newVisitorData = Environment.getInitialVisitorData().getOrNull()
            ?.takeIf { it.isNotBlank() && it != "null" }
            ?: return false
        Environment.visitorData = newVisitorData
        appContext().preferences.edit { putString(ytVisitorDataKey, newVisitorData) }
        return true
    }

    /**
     * Wrapper around the [NewPipeUtils.getSignatureTimestamp] function which reports exceptions
     */
    private fun getSignatureTimestampOrNull(
        videoId: String
    ): Int? {
        return NewPipeUtils.getSignatureTimestamp(videoId)
            .onFailure {
                Timber.e("SimplePlayer Could not get signature timestamp: $videoId")
                println("SimplePlayer Could not get signature timestamp: $videoId")
            }
            .getOrNull()
    }

    /**
     * Wrapper around the [NewPipeUtils.getStreamUrl] function which reports exceptions
     */
    private fun findUrlOrNull(
        format: PlayerResponse.StreamingData.Format,
        videoId: String
    ): String? {
        return NewPipeUtils.getStreamUrl(format, videoId)
            .onFailure {
                Timber.e("SimplePlayer Could not get stream url: $videoId")
                println("SimplePlayer Could not get stream url: $videoId")
            }
            .getOrNull()
    }
}
package com.aksa.mysignal

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlin.math.max

/** Plays a short ladder and records Ookla-style video fields. */
class VideoTestEngine(
    private val context: Context,
    private val playerView: PlayerView,
) {
    data class Result(
        val loadTimeMs: Long,
        val maxResolution: String,
        val bufferingPct: Double,
        val serverLabel: String,
    )

    private data class Stage(
        val loadMs: Long,
        val bufferMs: Long,
        val playedMs: Long,
        val stalled: Boolean,
    )

    suspend fun run(baseUrl: String, onPhase: (String) -> Unit): Result {
        val root = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        val player = ExoPlayer.Builder(context).build()
        playerView.player = player
        try {
            var loadTime = 0L
            var maxResolution = ""
            var bufferMs = 0L
            var playedMs = 0L
            for ((index, level) in levels.withIndex()) {
                val (file, label) = level
                onPhase(label)
                val stage = playLevel(player, "$root$file", index == 0)
                if (index == 0) loadTime = stage.loadMs
                bufferMs += stage.bufferMs
                playedMs += stage.playedMs
                if (stage.stalled) {
                    if (maxResolution.isEmpty()) maxResolution = label
                    break
                }
                maxResolution = label
            }
            if (maxResolution.isEmpty()) throw IllegalStateException("no video frame")
            val pct = if (playedMs <= 0L) 0.0 else (bufferMs * 100.0 / playedMs).coerceIn(0.0, 100.0)
            return Result(loadTime, maxResolution, pct, root)
        } finally {
            playerView.player = null
            player.release()
        }
    }

    private suspend fun playLevel(player: ExoPlayer, url: String, first: Boolean): Stage {
        var firstFrameAt = 0L
        var buffering = false
        var bufferAccum = 0L
        var bufferStart = 0L
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                if (firstFrameAt == 0L) firstFrameAt = SystemClock.elapsedRealtime()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val now = SystemClock.elapsedRealtime()
                if (playbackState == Player.STATE_BUFFERING) {
                    if (!buffering) {
                        buffering = true
                        bufferStart = now
                    }
                } else if (buffering) {
                    bufferAccum += rebufferMillis(bufferStart, now, firstFrameAt)
                    buffering = false
                }
            }
        }
        player.addListener(listener)
        val started = SystemClock.elapsedRealtime()
        player.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
        player.prepare()
        player.playWhenReady = true
        var stalled = false
        val deadline = started + if (first) 12_000L else 8_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            delay(100)
            if (firstFrameAt == 0L) continue
            val played = SystemClock.elapsedRealtime() - firstFrameAt
            if (bufferAccum > STALL_MS && played > 400L) {
                stalled = true
                break
            }
            if (played >= HOLD_MS && bufferAccum < STALL_MS) break
        }
        if (buffering) bufferAccum += rebufferMillis(bufferStart, SystemClock.elapsedRealtime(), firstFrameAt)
        player.removeListener(listener)
        player.stop()
        val played = if (firstFrameAt == 0L) 0L else SystemClock.elapsedRealtime() - firstFrameAt
        if (first && firstFrameAt == 0L) throw IllegalStateException("video did not start")
        val load = if (firstFrameAt == 0L) SystemClock.elapsedRealtime() - started else firstFrameAt - started
        return Stage(load, bufferAccum, max(played, 1L), stalled || firstFrameAt == 0L)
    }

    companion object {
        private const val HOLD_MS = 4_000L
        private const val STALL_MS = 800L
        private val levels = listOf("360.mp4" to "360p", "720.mp4" to "720p", "1080.mp4" to "1080p")

        fun defaultBaseUrl(): String =
            "https://${BuildConfig.COLLECTOR_HOST}/video/"
    }
}

/** Buffering before the first frame is load time, not a stall. */
internal fun rebufferMillis(bufferStart: Long, bufferEnd: Long, firstFrameAt: Long): Long {
    if (firstFrameAt == 0L || bufferEnd <= firstFrameAt) return 0L
    return max(0L, bufferEnd - max(bufferStart, firstFrameAt))
}

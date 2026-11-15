package app.hoot.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.util.Log
import app.hoot.R

object HootSoundManager {
    private const val TAG = "HootSound"
    private var soundPool: SoundPool? = null
    private var appContext: Context? = null
    private var flipId: Int = 0
    private var fanId: Int = 0
    private var cancelId: Int = 0
    private var dropId: Int = 0
    private var pressId: Int = 0
    private var squishId: Int = 0
    private var chimeId: Int = 0
    private val tickIds = IntArray(5)
    private var isLoaded = false
    private var lastTickTime = 0L

    fun init(context: Context) {
        appContext = context.applicationContext
        if (soundPool != null) return

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        soundPool = SoundPool.Builder()
            .setMaxStreams(10)
            .setAudioAttributes(attrs)
            .build()

        soundPool?.let { pool ->
            flipId = pool.load(context, R.raw.sound_flip, 1)
            fanId = pool.load(context, R.raw.sound_fan, 1)
            cancelId = pool.load(context, R.raw.sound_cancel, 1)
            dropId = pool.load(context, R.raw.sound_drop, 1)
            pressId = pool.load(context, R.raw.sound_press, 1)
            squishId = pool.load(context, R.raw.sound_squish, 1)
            chimeId = pool.load(context, R.raw.sound_chime, 1)

            tickIds[0] = pool.load(context, R.raw.sound_tick_0, 1)
            tickIds[1] = pool.load(context, R.raw.sound_tick_1, 1)
            tickIds[2] = pool.load(context, R.raw.sound_tick_2, 1)
            tickIds[3] = pool.load(context, R.raw.sound_tick_3, 1)
            tickIds[4] = pool.load(context, R.raw.sound_tick_4, 1)

            isLoaded = true
            Log.d(TAG, "HootSoundManager initialized on STREAM_MUSIC")
        }
    }

    private fun playFallback(resId: Int) {
        try {
            val ctx = appContext ?: return
            val mp = MediaPlayer.create(ctx, resId)
            mp?.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            mp?.setOnCompletionListener { it.release() }
            mp?.start()
        } catch (e: Exception) {
            Log.w(TAG, "Fallback playback failed for res $resId", e)
        }
    }

    fun playTick(variant: Int = 0) {
        val now = System.currentTimeMillis()
        if (now - lastTickTime < 35L) return
        lastTickTime = now

        val pool = soundPool ?: return
        val id = tickIds[variant.coerceIn(0, 4)]
        if (id != 0) {
            pool.play(id, 1.0f, 1.0f, 1, 0, 1.0f)
        }
    }

    fun playFlip() {
        Log.d(TAG, "playFlip")
        val pool = soundPool
        if (pool != null && flipId != 0) {
            val streamId = pool.play(flipId, 1.0f, 1.0f, 2, 0, 1.0f)
            if (streamId == 0) playFallback(R.raw.sound_flip)
        } else {
            playFallback(R.raw.sound_flip)
        }
    }

    fun playFan() {
        Log.d(TAG, "playFan")
        val pool = soundPool
        if (pool != null && fanId != 0) {
            val streamId = pool.play(fanId, 1.0f, 1.0f, 1, 0, 1.0f)
            if (streamId == 0) playFallback(R.raw.sound_fan)
        } else {
            playFallback(R.raw.sound_fan)
        }
    }

    fun playSwallow() {
        Log.d(TAG, "playSwallow (cancel gulp)")
        val pool = soundPool
        if (pool != null && cancelId != 0) {
            val streamId = pool.play(cancelId, 1.0f, 1.0f, 3, 0, 1.0f)
            if (streamId == 0) playFallback(R.raw.sound_cancel)
        } else {
            playFallback(R.raw.sound_cancel)
        }
    }

    fun playDrop() {
        Log.d(TAG, "playDrop")
        val pool = soundPool
        if (pool != null && dropId != 0) {
            val streamId = pool.play(dropId, 1.0f, 1.0f, 2, 0, 1.0f)
            if (streamId == 0) playFallback(R.raw.sound_drop)
        } else {
            playFallback(R.raw.sound_drop)
        }
    }

    fun playPress() {
        Log.d(TAG, "playPress")
        val pool = soundPool
        if (pool != null && pressId != 0) {
            val streamId = pool.play(pressId, 1.0f, 1.0f, 2, 0, 1.0f)
            if (streamId == 0) playFallback(R.raw.sound_press)
        } else {
            playFallback(R.raw.sound_press)
        }
    }

    fun playBlobPress() = playPress()

    fun playSquish() {
        Log.d(TAG, "playSquish")
        val pool = soundPool
        if (pool != null && squishId != 0) {
            val streamId = pool.play(squishId, 1.0f, 1.0f, 2, 0, 1.0f)
            if (streamId == 0) playFallback(R.raw.sound_squish)
        } else {
            playFallback(R.raw.sound_squish)
        }
    }

    fun playChime() {
        Log.d(TAG, "playChime")
        val pool = soundPool
        if (pool != null && chimeId != 0) {
            val streamId = pool.play(chimeId, 1.0f, 1.0f, 3, 0, 1.0f)
            if (streamId == 0) playFallback(R.raw.sound_chime)
        } else {
            playFallback(R.raw.sound_chime)
        }
    }

    fun playSuccess() = playChime()

    fun release() {
        soundPool?.release()
        soundPool = null
        appContext = null
        isLoaded = false
    }
}

package com.puretech.dialer

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.puretech.dialer.databinding.ActivityVoicemailGreetingBinding
import com.puretech.dialer.vvm.VvmPrefs
import com.puretech.dialer.vvm.VvmState
import com.puretech.dialer.vvm.VvmSync
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Records (or picks) a custom voicemail greeting and uploads it over IMAP.
 * See [com.puretech.dialer.vvm.ImapClient.appendGreeting] -- real protocol
 * confirmed 2026-08-04 by tracing the actual call chain in a decompiled
 * build of Google's own Play Store Phone app: `APPEND` to the `"GREETINGS"`
 * mailbox with the `$CNS-Greeting-On` flag and a set of `X-CNS-*` headers.
 *
 * Records raw AMR-NB (`audio/amr`) via [MediaRecorder] to match both what
 * real voicemails on this account already arrive as over IMAP, and what the
 * real client always encodes greetings as, up to 60s.
 */
class VoicemailGreetingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVoicemailGreetingBinding

    private var recorder: MediaRecorder? = null
    private var player: MediaPlayer? = null
    private var outputFile: File? = null
    private var mimeType: String = "audio/amr"
    private var durationSec: Long = 0L

    private enum class State { IDLE, RECORDING, REVIEW, PLAYING }
    private var state = State.IDLE

    private var startElapsedMs = 0L
    private val tickHandler = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            val elapsed = System.currentTimeMillis() - startElapsedMs
            updateTimer(elapsed)
            if (elapsed >= MAX_DURATION_MS) {
                stopRecording()
                return
            }
            tickHandler.postDelayed(this, 100)
        }
    }

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) usePickedFile(uri)
    }

    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) beginRecording() else showError(getString(R.string.greeting_error_record))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVoicemailGreetingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.back.setOnClickListener { finish() }
        binding.ball.setOnClickListener { onBallTapped() }
        binding.retry.setOnClickListener { resetToIdle() }
        binding.save.setOnClickListener { saveGreeting() }
        binding.playPreview.setOnClickListener { togglePreviewPlayback() }
        binding.chooseFile.setOnClickListener { pickFile.launch(arrayOf("audio/*")) }

        if (VvmPrefs.state(this) != VvmState.CONNECTED) {
            showError(getString(R.string.greeting_error_not_connected))
            binding.ball.isEnabled = false
            binding.chooseFile.isEnabled = false
        }
        updateUiForState()
    }

    override fun onDestroy() {
        super.onDestroy()
        tickHandler.removeCallbacks(tickRunnable)
        runCatching { recorder?.release() }
        runCatching { player?.release() }
    }

    private fun onBallTapped() {
        when (state) {
            State.IDLE -> {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    beginRecording()
                } else {
                    requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
            State.RECORDING -> stopRecording()
            else -> {}
        }
    }

    private fun beginRecording() {
        hideError()
        val file = File(cacheDir, "greeting_${System.currentTimeMillis()}.amr")
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION") MediaRecorder()
        }
        try {
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.AMR_NB)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.start()
        } catch (_: Exception) {
            runCatching { rec.release() }
            showError(getString(R.string.greeting_error_record))
            return
        }
        recorder = rec
        outputFile = file
        mimeType = "audio/amr"
        state = State.RECORDING
        startElapsedMs = System.currentTimeMillis()
        tickHandler.post(tickRunnable)
        updateUiForState()
    }

    private fun stopRecording() {
        tickHandler.removeCallbacks(tickRunnable)
        durationSec = ((System.currentTimeMillis() - startElapsedMs) / 1000).coerceAtMost(MAX_DURATION_SEC.toLong())
        try {
            recorder?.stop()
        } catch (_: Exception) {
        }
        runCatching { recorder?.release() }
        recorder = null
        state = State.REVIEW
        updateUiForState()
    }

    private fun resetToIdle() {
        runCatching { player?.release() }
        player = null
        outputFile?.delete()
        outputFile = null
        durationSec = 0L
        state = State.IDLE
        updateTimer(0)
        updateUiForState()
    }

    private fun togglePreviewPlayback() {
        val file = outputFile ?: return
        if (state == State.PLAYING) {
            runCatching { player?.stop() }
            runCatching { player?.release() }
            player = null
            state = State.REVIEW
            updateUiForState()
            return
        }
        try {
            val p = MediaPlayer()
            p.setDataSource(file.absolutePath)
            p.setOnCompletionListener {
                state = State.REVIEW
                updateUiForState()
            }
            p.prepare()
            p.start()
            player = p
            state = State.PLAYING
            updateUiForState()
        } catch (_: Exception) {
            showError(getString(R.string.greeting_error_file))
        }
    }

    private fun usePickedFile(uri: Uri) {
        hideError()
        try {
            val type = contentResolver.getType(uri) ?: "audio/*"
            val ext = when {
                type.contains("amr") -> "amr"
                type.contains("wav") -> "wav"
                else -> "mp3"
            }
            val file = File(cacheDir, "greeting_picked_${System.currentTimeMillis()}.$ext")
            val input = contentResolver.openInputStream(uri) ?: throw IllegalStateException("no stream")
            input.use { src -> FileOutputStream(file).use { dst -> src.copyTo(dst) } }
            outputFile = file
            mimeType = type
            durationSec = runCatching {
                MediaMetadataRetriever().use { retriever ->
                    retriever.setDataSource(file.absolutePath)
                    val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    (ms?.toLongOrNull() ?: 0L) / 1000
                }
            }.getOrDefault(0L)
            state = State.REVIEW
            updateUiForState()
        } catch (_: Exception) {
            showError(getString(R.string.greeting_error_file))
        }
    }

    private fun saveGreeting() {
        val file = outputFile ?: return
        binding.save.isEnabled = false
        binding.retry.isEnabled = false
        binding.uploadProgress.visibility = View.VISIBLE
        val ctx = applicationContext
        val bytes = file.readBytes()
        val type = mimeType
        val duration = durationSec
        Thread {
            val ok = try {
                VvmSync.uploadGreeting(ctx, bytes, type, duration)
            } catch (_: Throwable) {
                false
            }
            ui {
                binding.uploadProgress.visibility = View.GONE
                binding.save.isEnabled = true
                binding.retry.isEnabled = true
                if (ok) {
                    Toast.makeText(this, R.string.greeting_saved, Toast.LENGTH_LONG).show()
                    finish()
                } else {
                    showError(getString(R.string.greeting_upload_failed))
                }
            }
        }.start()
    }

    private fun updateTimer(elapsedMs: Long) {
        val seconds = (elapsedMs / 1000).toInt().coerceAtMost(MAX_DURATION_SEC)
        binding.timer.text = String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)
        binding.progressRing.progress = elapsedMs.toFloat() / MAX_DURATION_MS
    }

    private fun updateUiForState() {
        when (state) {
            State.IDLE -> {
                binding.ballIcon.setImageResource(R.drawable.ic_mic)
                binding.ball.setBackgroundResource(R.drawable.bg_circle_green)
                binding.halo.setBackgroundResource(R.drawable.bg_circle_halo_green)
                binding.hint.text = getString(R.string.greeting_tap_to_record)
                binding.progressRing.progress = 0f
                binding.reviewActions.visibility = View.GONE
                binding.playPreview.visibility = View.GONE
                binding.chooseFile.visibility = View.VISIBLE
            }
            State.RECORDING -> {
                binding.ballIcon.setImageResource(R.drawable.ic_stop_square)
                binding.ball.setBackgroundResource(R.drawable.bg_circle_record_active)
                binding.halo.setBackgroundResource(R.drawable.bg_circle_halo_red)
                binding.hint.text = getString(R.string.greeting_stop)
                binding.reviewActions.visibility = View.GONE
                binding.playPreview.visibility = View.GONE
                binding.chooseFile.visibility = View.GONE
            }
            State.REVIEW, State.PLAYING -> {
                binding.ballIcon.setImageResource(
                    if (state == State.PLAYING) R.drawable.ic_stop_square else R.drawable.ic_mic
                )
                binding.ball.setBackgroundResource(R.drawable.bg_circle_green)
                binding.halo.setBackgroundResource(R.drawable.bg_circle_halo_green)
                binding.hint.text = ""
                binding.reviewActions.visibility = View.VISIBLE
                binding.playPreview.visibility = View.VISIBLE
                binding.playPreview.text = getString(
                    if (state == State.PLAYING) R.string.greeting_pause else R.string.greeting_play
                )
                binding.chooseFile.visibility = View.GONE
            }
        }
    }

    private fun showError(message: String) {
        binding.greetingError.text = message
        binding.greetingError.visibility = View.VISIBLE
    }

    private fun hideError() {
        binding.greetingError.visibility = View.GONE
    }

    private fun ui(block: () -> Unit) = binding.root.post(block)

    companion object {
        private const val MAX_DURATION_SEC = 60
        private const val MAX_DURATION_MS = MAX_DURATION_SEC * 1000L
    }
}

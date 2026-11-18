package com.bigopeforcebit.monika.voice

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.bigopeforcebit.monika.R
import com.bigopeforcebit.monika.settings.FeatureFlags
import com.bigopeforcebit.monika.ui.MainActivity

/**
 * Listens for the configured spoken wake word (default "Monika") and opens the app with voice
 * input ready. The service only runs while the user has the wake-word setting enabled.
 */
class WakeWordService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var paused = false
    private var stopped = false

    private val restart = Runnable {
        if (!stopped && enabled(this) && !paused) startListening()
    }
    private val resume = Runnable {
        paused = false
        if (!stopped && enabled(this)) startListening()
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            if (heardWakeWord(partialResults)) onWake()
        }

        override fun onResults(results: Bundle?) {
            if (heardWakeWord(results)) onWake()
        }

        override fun onEndOfSpeech() {
            listening = false
            scheduleRestart()
        }

        override fun onError(error: Int) {
            listening = false
            scheduleRestart()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Acknowledge the OS start first so Android never kills us before foreground promotion.
        promoteForeground(notification(getString(R.string.wake_word_listening)))
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED || !enabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        startListening()
        return START_STICKY
    }

    private fun promoteForeground(note: Notification) {
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, note)
            }
        }
    }

    private fun startListening() {
        if (stopped || paused || listening) return
        val recognizer = this.recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also { this.recognizer = it }
        recognizer.setRecognitionListener(listener)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        listening = runCatching { recognizer.startListening(intent); true }.getOrDefault(false)
        if (!listening) scheduleRestart()
    }

    private fun scheduleRestart() {
        handler.removeCallbacks(restart)
        if (!stopped && enabled(this) && !paused) handler.postDelayed(restart, 700L)
    }

    private fun heardWakeWord(bundle: Bundle?): Boolean {
        val results = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return false
        val phrase = FeatureFlags.get(this).wakeWordPhrase().cleanWords()
        if (phrase.isEmpty()) return false
        return results.any { raw ->
            val words = raw.cleanWords()
            // "hey/ok/okay <phrase>" is accepted as well as the bare phrase.
            val spoken = if (words.isNotEmpty() && words[0] in WAKE_PREFIXES) words.drop(1) else words
            spoken.windowed(phrase.size).any { window -> window == phrase }
        }
    }

    /** Lowercase, split on whitespace and drop trailing punctuation. */
    private fun String.cleanWords(): List<String> = lowercase()
        .split(WHITESPACE).filter { it.isNotEmpty() }
        .map { it.trim('.', ',', '!', '?', '\'', '"') }

    private fun onWake() {
        listening = false
        recognizer?.cancel()
        paused = true
        handler.removeCallbacks(restart)
        handler.removeCallbacks(resume)
        handler.postDelayed(resume, COOLDOWN_MS)
        runCatching {
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(getString(R.string.wake_word_triggered)))
        }
        val open = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_START_VOICE, true)
        }
        runCatching { startActivity(open) }
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 2,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 3,
            Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.wake_word_stop), stop).build())
            .apply { if (Build.VERSION.SDK_INT >= 31) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE) }
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.wake_word_channel), NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        })
    }

    override fun onDestroy() {
        stopped = true
        handler.removeCallbacks(restart)
        handler.removeCallbacks(resume)
        runCatching { recognizer?.destroy() }
        recognizer = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "monika_wake_word"
        private const val NOTIFICATION_ID = 90_119
        private const val ACTION_STOP = "com.bigopeforcebit.monika.voice.STOP_WAKE_WORD"
        private const val COOLDOWN_MS = 12_000L
        private val WAKE_PREFIXES = setOf("hey", "ok", "okay")
        private val WHITESPACE = Regex("\\s+")

        fun enabled(context: Context): Boolean = FeatureFlags.get(context).wakeWord()

        fun start(context: Context) {
            val intent = Intent(context, WakeWordService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeWordService::class.java))
        }
    }
}

package com.groqandroid

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import kotlin.math.abs

class TranscriptionOverlayService : AccessibilityService() {

    companion object {
        const val CHANNEL_ID = "transcription_overlay_channel"
        const val NOTIFICATION_ID = 2001
        const val ACTION_STOP = "com.groqandroid.STOP_OVERLAY"
        const val ACTION_TOGGLE_BUBBLE = "com.groqandroid.TOGGLE_BUBBLE"
        const val ACTION_SHOW_BUBBLE = "com.groqandroid.SHOW_BUBBLE"
        private const val PREFS_NAME = "bubble_prefs"
        private const val KEY_BUBBLE_X = "bubble_x"
        private const val KEY_BUBBLE_Y = "bubble_y"
        private const val TAP_THRESHOLD_DP = 28
        private const val KEEPALIVE_INTERVAL_MS = 30_000L

        val LANGUAGE_OPTIONS = listOf(
            "Auto-detect" to null,
            "Nederlands" to "nl",
            "English" to "en",
            "Deutsch" to "de",
            "Français" to "fr",
            "Español" to "es",
            "Italiano" to "it",
            "Português" to "pt",
            "日本語" to "ja",
            "中文" to "zh",
            "한국어" to "ko",
            "العربية" to "ar",
            "Türkçe" to "tr",
            "Polski" to "pl",
            "Русский" to "ru",
            "Slovenčina" to "sk"
        )
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var wakeLock: PowerManager.WakeLock? = null
    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private lateinit var audioRecorder: AudioRecorder
    private var apiClient: GroqApiClient? = null
    private var recordingJob: Job? = null
    private var transcriptionJob: Job? = null

    private enum class State { IDLE, RECORDING, PROCESSING }
    private var state = State.IDLE
    @Volatile
    private var tapGuard = false
    private var lastInsertedText: String? = null

    private var bubbleVisible = false
    private var bubbleEnabled = false // user toggle from settings
    private var isTouchActive = false
    private var tapThresholdPx = 10f // computed in onServiceConnected
    private var longPressHandler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null
    private val focusCheckHandler = Handler(Looper.getMainLooper())
    private val focusCheckRunnable = Runnable {
        updateBubbleAlpha()
    }


    // Keepalive: re-attach bubble if OS removes the view
    private val keepaliveHandler = Handler(Looper.getMainLooper())
    private val keepaliveRunnable = object : Runnable {
        override fun run() {
            if (bubbleEnabled && bubbleVisible && bubbleView?.windowToken == null) {
                // View was detached by OS — re-add it
                bubbleVisible = false
                showBubble()
            }
            keepaliveHandler.postDelayed(this, KEEPALIVE_INTERVAL_MS)
        }
    }

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_STOP -> {
                    hideBubble()
                    disableSelf()
                }
                ACTION_TOGGLE_BUBBLE -> {
                    val prefs = try { getEncryptedPrefs() } catch (_: Exception) { null }
                    bubbleEnabled = prefs?.getBoolean(SettingsActivity.KEY_BUBBLE_ENABLED, false) == true
                    if (bubbleEnabled) {
                        showBubble()
                    } else {
                        hideBubble()
                    }
                }
                ACTION_SHOW_BUBBLE -> {
                    bubbleEnabled = true
                    try { getEncryptedPrefs().edit().putBoolean(SettingsActivity.KEY_BUBBLE_ENABLED, true).apply() } catch (_: Exception) {}
                    showBubble()
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // WakeLock to prevent MIUI from killing the service
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GroqAndroid::AccessibilityWakeLock")
        wakeLock?.acquire()
        audioRecorder = AudioRecorder(cacheDir)
        audioRecorder.onMaxDurationReached = {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this, "Max duration reached (2 min)", Toast.LENGTH_SHORT).show()
                stopRecording()
            }
        }
        tapThresholdPx = TAP_THRESHOLD_DP * resources.displayMetrics.density
        // no init needed for audio cues
        createNotificationChannel()

        val filter = IntentFilter().apply {
            addAction(ACTION_STOP)
            addAction(ACTION_TOGGLE_BUBBLE)
            addAction(ACTION_SHOW_BUBBLE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(commandReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(commandReceiver, filter)
        }

        // Show bubble if enabled in settings
        val prefs = try { getEncryptedPrefs() } catch (_: Exception) { null }
        bubbleEnabled = prefs?.getBoolean(SettingsActivity.KEY_BUBBLE_ENABLED, false) == true
        if (bubbleEnabled) {
            showBubble()
        }

        // Start keepalive checker
        keepaliveHandler.postDelayed(keepaliveRunnable, KEEPALIVE_INTERVAL_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return Service.START_STICKY
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!bubbleEnabled || !bubbleVisible) return
        when (event?.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                focusCheckHandler.removeCallbacks(focusCheckRunnable)
                focusCheckHandler.postDelayed(focusCheckRunnable, 100)
            }
        }
    }

    override fun onInterrupt() {
        // Service interrupted — immediately try to re-show the bubble
        if (bubbleEnabled) {
            if (bubbleView?.windowToken == null) {
                bubbleVisible = false
                showBubble()
            }
        }
        // Also restart keepalive check
        keepaliveHandler.removeCallbacks(keepaliveRunnable)
        keepaliveHandler.postDelayed(keepaliveRunnable, 5_000)
    }

    override fun onDestroy() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        focusCheckHandler.removeCallbacks(focusCheckRunnable)
        keepaliveHandler.removeCallbacks(keepaliveRunnable)
        try { unregisterReceiver(commandReceiver) } catch (_: Exception) {}
        @Suppress("DEPRECATION")
        stopForeground(true)
        hideBubble()
        try {
            if (state == State.RECORDING) {
                audioRecorder.stop()
            }
        } catch (_: Exception) {}
        apiClient?.shutdown()
        scope.cancel()
        super.onDestroy()
    }

    // --- Notification ---

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Voice Input Overlay",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Floating microphone for voice input"
            setShowBadge(false)
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(bubbleHidden: Boolean = false): Notification {
        val stopIntent = Intent(ACTION_STOP).apply { setPackage(packageName) }
        val stopPending = PendingIntent.getBroadcast(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return if (bubbleHidden) {
            val showIntent = Intent(ACTION_SHOW_BUBBLE).apply { setPackage(packageName) }
            val showPending = PendingIntent.getBroadcast(
                this, 1, showIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle("Floating microphone hidden")
                .setContentText("Tap to show the floating microphone")
                .setContentIntent(showPending)
                .addAction(Notification.Action.Builder(null, "Show", showPending).build())
                .addAction(Notification.Action.Builder(null, "Stop", stopPending).build())
                .setOngoing(true)
                .build()
        } else {
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle("GroqAndroid Voice Input")
                .setContentText("Floating microphone active")
                .addAction(Notification.Action.Builder(null, "Stop", stopPending).build())
                .setOngoing(true)
                .build()
        }
    }

    private fun showNotification(bubbleHidden: Boolean = false) {
        val notification = buildNotification(bubbleHidden)
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun hideNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFICATION_ID)
    }

    // --- Bubble overlay ---

    fun showBubble() {
        if (bubbleVisible) return

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val bubbleSize = (56 * resources.displayMetrics.density).toInt()
        val edgeMargin = (16 * resources.displayMetrics.density).toInt()
        val screenWidth = resources.displayMetrics.widthPixels

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val defaultX = screenWidth - bubbleSize - edgeMargin
        val rawSavedX = prefs.getInt(KEY_BUBBLE_X, defaultX)
        val savedX = if (rawSavedX <= 0) defaultX else rawSavedX.coerceIn(edgeMargin, (screenWidth - bubbleSize - edgeMargin).coerceAtLeast(edgeMargin))
        val savedY = prefs.getInt(KEY_BUBBLE_Y, resources.displayMetrics.heightPixels / 3)

        // MIUI/Xiaomi doesn't deliver touch events for TYPE_ACCESSIBILITY_OVERLAY.
        // Use TYPE_APPLICATION_OVERLAY when overlay permission is granted, fall back otherwise.
        val overlayType = if (android.provider.Settings.canDrawOverlays(this)) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        }

        layoutParams = WindowManager.LayoutParams(
            1, 1,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX
            y = savedY
        }

        bubbleView = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            setBackgroundResource(R.drawable.bubble_bg)
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            elevation = 12 * resources.displayMetrics.density
            isClickable = true
            alpha = 0.0f
        }

        setupTouchListener()

        try {
            windowManager?.addView(bubbleView, layoutParams)
            bubbleVisible = true
            focusCheckHandler.postDelayed(focusCheckRunnable, 300)
            // Start as foreground service to prevent Android from killing us
            val notification = buildNotification(bubbleHidden = false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Could not show bubble: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun hideBubble() {
        if (!bubbleVisible) return

        // Stop recording if active
        if (state == State.RECORDING) {
            try { audioRecorder.stop() } catch (_: Exception) {}
            state = State.IDLE
        }

        try {
            windowManager?.removeView(bubbleView)
        } catch (_: Exception) {}
        bubbleView = null
        bubbleVisible = false

        if (bubbleEnabled) {
            // Manual hide: update notification to show "tap to show"
            val notification = buildNotification(bubbleHidden = true)
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, notification)
        } else {
            // Bubble disabled entirely — stop foreground service
            @Suppress("DEPRECATION")
            stopForeground(true)
            hideNotification()
        }
    }

    private fun setupTouchListener() {
        val view = bubbleView ?: return
        val params = layoutParams ?: return

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false
        var isLongPress = false

        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isTouchActive = true
                    vibrate(20)
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    isLongPress = false
                    // Full opacity and feedback on press
                    v.alpha = 1.0f
                    v.scaleX = 0.92f
                    v.scaleY = 0.92f
                    // Start long-press timer (only when idle)
                    if (state == State.IDLE) {
                        longPressRunnable = Runnable {
                            isLongPress = true
                            isTouchActive = true
                            v.alpha = 1.0f
                            v.scaleX = 1.0f
                            v.scaleY = 1.0f
                            vibrate(30)
                            showLanguagePopup(v)
                        }
                        longPressHandler.postDelayed(longPressRunnable!!, 600)
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = abs(event.rawX - initialTouchX)
                    val dy = abs(event.rawY - initialTouchY)
                    if (dx > tapThresholdPx || dy > tapThresholdPx) {
                        isDragging = true
                        isLongPress = false
                        longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
                        // Restore visual state during drag
                        v.alpha = 1.0f
                        v.scaleX = 1.0f
                        v.scaleY = 1.0f
                    }
                    if (isDragging) {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        try {
                            windowManager?.updateViewLayout(v, params)
                        } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
                    // Restore visual state
                    v.alpha = 1.0f
                    v.scaleX = 1.0f
                    v.scaleY = 1.0f

                    if (isLongPress) {
                        // Long press handled — language popup is shown, isTouchActive remains true until popup dismisses
                    } else {
                        isTouchActive = false
                        if (!isDragging) {
                            onBubbleTapped()
                        } else {
                            snapToEdge()
                        }
                    }
                    savePosition()
                    updateBubbleAlpha()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
                    isTouchActive = false
                    v.scaleX = 1.0f
                    v.scaleY = 1.0f
                    updateBubbleAlpha()
                    true
                }
                else -> true
            }
        }
    }

    private fun showLanguagePopup(anchor: View) {
        try {
            val wrapper = ContextThemeWrapper(this, com.google.android.material.R.style.Theme_MaterialComponents_DayNight_NoActionBar)
            val popup = PopupMenu(wrapper, anchor)
            LANGUAGE_OPTIONS.forEachIndexed { index, (name, _) ->
                popup.menu.add(0, index, index, name)
            }
            // Add "Verbergen" as last item
            popup.menu.add(0, LANGUAGE_OPTIONS.size, LANGUAGE_OPTIONS.size, "Verbergen")

            popup.setOnDismissListener {
                isTouchActive = false
                updateBubbleAlpha()
            }

            popup.setOnMenuItemClickListener { item ->
                if (item.itemId == LANGUAGE_OPTIONS.size) {
                    // "Verbergen" selected
                    hideBubble()
                } else {
                    val (name, code) = LANGUAGE_OPTIONS[item.itemId]
                    try {
                        val prefs = getEncryptedPrefs()
                        if (code != null) {
                            prefs.edit().putString(SettingsActivity.KEY_LANGUAGE, code).apply()
                        } else {
                            prefs.edit().remove(SettingsActivity.KEY_LANGUAGE).apply()
                        }
                        Toast.makeText(this, name, Toast.LENGTH_SHORT).show()
                    } catch (_: Exception) {}
                }
                true
            }
            popup.show()
        } catch (e: Exception) {
            isTouchActive = false
            updateBubbleAlpha()
        }
    }

    private fun snapToEdge() {
        val params = layoutParams ?: return
        val screenWidth = resources.displayMetrics.widthPixels
        val bubbleSize = (56 * resources.displayMetrics.density).toInt()
        val edgeMargin = (16 * resources.displayMetrics.density).toInt()
        val centerX = params.x + bubbleSize / 2

        params.x = if (centerX < screenWidth / 2) edgeMargin else (screenWidth - bubbleSize - edgeMargin).coerceAtLeast(edgeMargin)

        try {
            windowManager?.updateViewLayout(bubbleView, params)
        } catch (_: Exception) {}
    }

    private fun savePosition() {
        val params = layoutParams ?: return
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_BUBBLE_X, params.x)
            .putInt(KEY_BUBBLE_Y, params.y)
            .apply()
    }

    // --- Recording & transcription ---

    private fun vibrate(durationMs: Long = 30) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(VIBRATOR_SERVICE) as Vibrator
                v.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (_: Exception) {}
    }

    private fun onBubbleTapped() {
        // Guard against rapid double-taps
        if (tapGuard) return
        tapGuard = true
        Handler(Looper.getMainLooper()).postDelayed({ tapGuard = false }, 300)

        when (state) {
            State.IDLE -> startRecording()
            State.RECORDING -> stopRecording()
            State.PROCESSING -> {
                // Cancel ongoing transcription
                transcriptionJob?.cancel()
                state = State.IDLE
                updateBubbleState()
                Toast.makeText(this, "Transcription cancelled", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startRecording() {
        // Check microphone permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "Microphone permission needed — open GroqAndroid app to grant", Toast.LENGTH_LONG).show()
            try {
                val intent = Intent(this, PermissionActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Could not request permission: ${e.message}", Toast.LENGTH_SHORT).show()
            }
            return
        }

        // Check API key
        val apiKey = try { getApiKey() } catch (e: Exception) {
            Toast.makeText(this, "Could not read settings: ${e.message}", Toast.LENGTH_LONG).show()
            null
        }
        if (apiKey.isNullOrEmpty()) {
            Toast.makeText(this, "Set up an API key first in the GroqAndroid app", Toast.LENGTH_LONG).show()
            return
        }
        val endpoint = getCustomEndpoint()
        apiClient = if (endpoint != null) GroqApiClient(apiKey, endpoint) else GroqApiClient(apiKey)

        // Change to recording state immediately for visual feedback
        state = State.RECORDING
        updateBubbleState()
        if (isHapticEnabled()) vibrate(30)
        if (isSoundEnabled()) scope.launch(Dispatchers.IO) { playDictationCue(floatArrayOf(523.25f, 659.25f)) }

        recordingJob = scope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(250) // let the cue finish before recording
            try {
                audioRecorder.record()
            } catch (e: Exception) {
                launch(Dispatchers.Main) {
                    Toast.makeText(this@TranscriptionOverlayService, "Recording error: ${e.message}", Toast.LENGTH_LONG).show()
                    state = State.IDLE
                    updateBubbleState()
                }
            }
        }
    }

    private fun stopRecording() {
        if (state != State.RECORDING) return
        try { audioRecorder.stop() } catch (_: Exception) {}
        state = State.PROCESSING
        updateBubbleState()
        if (isHapticEnabled()) vibrate(50)

        transcriptionJob = scope.launch {
            try {
                kotlinx.coroutines.withTimeout(60_000) {
                    recordingJob?.join()

                    // Skip transcription for very short recordings to avoid Whisper hallucination
                    val fileSize = audioRecorder.outputFile.length()
                    if (fileSize < 32000) { // ~1 second of 16kHz mono 16-bit + WAV header
                        launch(Dispatchers.Main) {
                            Toast.makeText(this@TranscriptionOverlayService, "Recording too short", Toast.LENGTH_SHORT).show()
                        }
                        return@withTimeout
                    }

                    val language = getLanguage()
                    val dictionary = getDictionary()
                    val model = getWhisperModel()
                    val rawText = apiClient?.transcribe(audioRecorder.outputFile, language, dictionary, model) ?: ""
                    var text = applyReplacements(rawText)
                    if (text.isNotBlank() && isLlmEnabled()) {
                        val llmPrompt = getLlmPrompt()
                        val llmModel = getLlmModel()
                        text = try {
                            kotlinx.coroutines.withTimeout(30_000L) {
                                apiClient?.postProcessText(text, llmPrompt, llmModel) ?: text
                            }
                        } catch (_: Exception) {
                            text
                        }
                        text = applyReplacements(text)
                    }
                    if (isChatModeEnabled()) {
                        text = stripTrailingPeriod(text)
                    }

                    if (text.isNotEmpty()) {
                        insertTextAtCursor(text + " ")
                        lastInsertedText = text + " "
                        if (isSoundEnabled()) launch(Dispatchers.IO) { playDictationCue(floatArrayOf(587.33f, 440f)) }
                    } else {
                        Toast.makeText(this@TranscriptionOverlayService, "No speech detected", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                Toast.makeText(this@TranscriptionOverlayService, "Timed out — try again", Toast.LENGTH_SHORT).show()
            } catch (e: AuthenticationException) {
                Toast.makeText(this@TranscriptionOverlayService, "Invalid API key — check Settings", Toast.LENGTH_LONG).show()
            } catch (e: TranscriptionException) {
                Toast.makeText(this@TranscriptionOverlayService, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@TranscriptionOverlayService, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                audioRecorder.cleanup()
                state = State.IDLE
                updateBubbleState()
            }
        }
    }

    private fun checkIfInputFieldFocused(): Boolean {
        return try {
            val root = rootInActiveWindow ?: return false
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null) {
                val isEditable = focused.isEditable ||
                    focused.className?.contains("EditText", ignoreCase = true) == true ||
                    (focused.isFocusable && focused.className?.contains("Edit", ignoreCase = true) == true)
                @Suppress("DEPRECATION")
                focused.recycle()
                @Suppress("DEPRECATION")
                root.recycle()
                isEditable
            } else {
                @Suppress("DEPRECATION")
                root.recycle()
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun updateBubbleAlpha(forceAlpha: Float? = null) {
        val view = bubbleView ?: return
        val params = layoutParams ?: return

        val shouldBeActive = forceAlpha != null || state != State.IDLE || isTouchActive || checkIfInputFieldFocused()

        if (shouldBeActive) {
            val fullSize = (56 * resources.displayMetrics.density).toInt()
            if (params.width != fullSize || params.height != fullSize || (params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0) {
                params.width = fullSize
                params.height = fullSize
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                try {
                    windowManager?.updateViewLayout(view, params)
                } catch (_: Exception) {}
            }
            view.animate().alpha(forceAlpha ?: 1.0f).setDuration(150).start()
        } else {
            if (params.width != 1 || params.height != 1 || (params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0) {
                params.width = 1
                params.height = 1
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                try {
                    windowManager?.updateViewLayout(view, params)
                } catch (_: Exception) {}
            }
            view.animate().cancel()
            view.alpha = 0.0f
        }
    }

    private fun updateBubbleState() {
        val view = bubbleView ?: return
        view.setBackgroundResource(
            when (state) {
                State.IDLE -> R.drawable.bubble_bg
                State.RECORDING -> R.drawable.mic_button_recording
                State.PROCESSING -> R.drawable.mic_button_processing
            }
        )
        updateBubbleAlpha()
    }

    // --- Text insertion via accessibility ---

    private fun insertTextAtCursor(text: String) {
        try {
            // Always copy to clipboard so the user can paste manually if needed
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("transcription", text))

            val focusedNode = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focusedNode == null) {
                // No focused node found — fallback to clipboard paste
                pasteViaClipboard(text)
                return
            }

            // Notes uses a long editor inside a ScrollView. Replacing all text moves
            // the cursor to the document end and can scroll before selection is restored.
            if (focusedNode.packageName?.toString() == "it.niedermann.owncloud.notes") {
                val pasted = focusedNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                if (!pasted) {
                    Toast.makeText(this, "Text copied. Paste manually in Notes.", Toast.LENGTH_SHORT).show()
                }
                @Suppress("DEPRECATION")
                focusedNode.recycle()
                return
            }

            // Try ACTION_SET_TEXT first (works for most native apps)
            val success = trySetText(focusedNode, text)

            if (!success) {
                // Fallback for apps like Claude that use WebView/custom text fields
                pasteViaClipboard(text)
                Toast.makeText(this, "Pasted via clipboard", Toast.LENGTH_SHORT).show()
            }

            @Suppress("DEPRECATION")
            focusedNode.recycle()
        } catch (e: Exception) {
            // Last resort fallback
            try { pasteViaClipboard(text) } catch (_: Exception) {
                Toast.makeText(this, "Could not insert text: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun trySetText(focusedNode: AccessibilityNodeInfo, text: String): Boolean {
        // Many apps (Signal, WhatsApp) return hint/placeholder as node.text when empty.
        val rawText = focusedNode.text?.toString() ?: ""
        val hintText = focusedNode.hintText?.toString()
        val rawSelStart = focusedNode.textSelectionStart
        val rawSelEnd = focusedNode.textSelectionEnd
        val isHintOnly = rawText == hintText || (rawText.isNotEmpty() && rawSelStart == -1 && rawSelEnd == -1)
        val currentText = if (isHintOnly) "" else rawText
        val selStart = if (isHintOnly) 0 else rawSelStart.coerceAtLeast(0)
        val selEnd = if (isHintOnly) 0 else rawSelEnd.coerceAtLeast(selStart)

        val newText = StringBuilder(currentText)
            .replace(selStart, selEnd, text)
            .toString()

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        }
        val result = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

        if (result) {
            // Move cursor to after inserted text
            val newCursorPos = selStart + text.length
            val selArgs = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newCursorPos)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newCursorPos)
            }
            focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)
        }

        return result
    }

    private fun pasteViaClipboard(text: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("transcription", text))
        val focusedNode = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusedNode != null) {
            focusedNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            @Suppress("DEPRECATION")
            focusedNode.recycle()
        }
    }

    // --- Audio Cues via AudioTrack ---

    private fun isHapticEnabled(): Boolean {
        return try {
            val prefs = getEncryptedPrefs()
            prefs.getBoolean(SettingsActivity.KEY_HAPTIC_ENABLED, true)
        } catch (_: Exception) {
            true
        }
    }

    private fun isSoundEnabled(): Boolean {
        return try {
            val prefs = getEncryptedPrefs()
            prefs.getBoolean(SettingsActivity.KEY_SOUND_ENABLED, true)
        } catch (_: Exception) {
            true
        }
    }

    private fun getApiKey(): String? {
        val prefs = getEncryptedPrefs()
        return prefs.getString(SettingsActivity.KEY_API_KEY, null)
    }

    private fun getCustomEndpoint(): String? {
        val prefs = getEncryptedPrefs()
        val url = prefs.getString(SettingsActivity.KEY_API_ENDPOINT, null)
        return if (!url.isNullOrBlank()) url.trim() else null
    }

    private fun getLanguage(): String? {
        val prefs = getEncryptedPrefs()
        return prefs.getString(SettingsActivity.KEY_LANGUAGE, null)
    }

    private fun getDictionary(): String? {
        val prefs = getEncryptedPrefs()
        val jsonStr = prefs.getString(SettingsActivity.KEY_DICTIONARY, null) ?: return null
        return try {
            val jsonArray = JSONArray(jsonStr)
            val list = mutableListOf<String>()
            for (i in 0 until jsonArray.length()) {
                val word = jsonArray.getString(i)
                if (word.isNotBlank()) list.add(word.trim())
            }
            if (list.isNotEmpty()) list.joinToString(", ") else null
        } catch (_: Exception) {
            null
        }
    }

    private fun getWhisperModel(): String {
        val prefs = getEncryptedPrefs()
        return prefs.getString(SettingsActivity.KEY_WHISPER_MODEL, "whisper-large-v3") ?: "whisper-large-v3"
    }

    private fun isLlmEnabled(): Boolean {
        return try {
            getEncryptedPrefs().getBoolean(SettingsActivity.KEY_LLM_ENABLED, true)
        } catch (_: Exception) {
            true
        }
    }

    private fun getLlmModel(): String {
        return try {
            val model = getEncryptedPrefs().getString(SettingsActivity.KEY_LLM_MODEL, null)
            if (model.isNullOrBlank() || model !in SettingsActivity.VALID_LLM_MODELS) {
                SettingsActivity.DEFAULT_LLM_MODEL
            } else {
                model
            }
        } catch (_: Exception) {
            SettingsActivity.DEFAULT_LLM_MODEL
        }
    }

    private fun getLlmPrompt(): String {
        return try {
            val prompt = getEncryptedPrefs().getString(SettingsActivity.KEY_LLM_PROMPT, null)
            if (prompt.isNullOrBlank()) SettingsActivity.DEFAULT_LLM_PROMPT else prompt
        } catch (_: Exception) {
            SettingsActivity.DEFAULT_LLM_PROMPT
        }
    }

    private fun isChatModeEnabled(): Boolean {
        val prefs = getEncryptedPrefs()
        return prefs.getBoolean(SettingsActivity.KEY_CHAT_MODE, false)
    }

    private fun applyReplacements(text: String): String {
        val prefs = getEncryptedPrefs()
        val jsonStr = prefs.getString(SettingsActivity.KEY_REPLACEMENTS, null) ?: return text
        return try {
            val jsonArray = JSONArray(jsonStr)
            var modified = text
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val target = obj.getString("target")
                val replacement = obj.getString("replacement")
                if (target.isNotBlank()) {
                    modified = modified.replace(target, replacement, ignoreCase = true)
                }
            }
            modified
        } catch (_: Exception) {
            text
        }
    }

    private fun stripTrailingPeriod(text: String): String {
        val trimmed = text.trimEnd()
        return if (trimmed.endsWith('.')) {
            trimmed.substring(0, trimmed.length - 1)
        } else {
            trimmed
        }
    }

    private fun getEncryptedPrefs() = EncryptedSharedPreferences.create(
        this,
        SettingsActivity.PREFS_NAME,
        MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private fun playDictationCue(frequencies: FloatArray) {
        try {
            val sampleRate = 16000
            val durationMs = 80
            val numSamples = (sampleRate * durationMs) / 1000
            val buffer = ByteArray(numSamples * 2)
            val rampSamples = sampleRate * 10 / 1000 // 10ms ramp for click-free audio

            for (freq in frequencies) {
                for (i in 0 until numSamples) {
                    val time = i.toDouble() / sampleRate
                    var envelope = 1.0
                    if (i < rampSamples) {
                        envelope = i.toDouble() / rampSamples
                    } else if (i > numSamples - rampSamples) {
                        envelope = (numSamples - i).toDouble() / rampSamples
                    }
                    val sample = kotlin.math.sin(2.0 * Math.PI * freq * time) * 32767.0 * 0.15 * envelope
                    val sInt = sample.toInt().toShort()
                    buffer[i * 2] = (sInt.toInt() and 0xff).toByte()
                    buffer[i * 2 + 1] = (sInt.toInt() ushr 8 and 0xff).toByte()
                }
            }

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(buffer.size)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            track.write(buffer, 0, buffer.size)
            track.play()
            Thread.sleep((durationMs * frequencies.size + 20).toLong())
            track.stop()
            track.release()
        } catch (_: Exception) {}
    }
}

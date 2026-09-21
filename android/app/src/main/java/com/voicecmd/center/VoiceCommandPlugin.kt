package com.voicecmd.center

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.provider.Settings
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.PermissionState
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import org.json.JSONArray
import org.json.JSONObject

/**
 * The bridge between the web UI and the phone.
 *
 * One loop per command:
 *   speech -> transcript -> Needle 3 (tool call) -> confidence gate -> Executor -> outcome
 *
 * The confidence gate follows Needle's documented act/confirm/refuse contract, which
 * happens to line up with what Android permits: act when the model is sure and the
 * platform allows it, confirm when the model is unsure or the user still has a tap to
 * make, refuse when nothing covers the request. Empty `function_calls` is a refusal —
 * Needle generates no free text, so there is no fallback to invent.
 */
@CapacitorPlugin(
    name = "VoiceCommand",
    permissions = [
        Permission(alias = "microphone", strings = [Manifest.permission.RECORD_AUDIO]),
        Permission(alias = "contacts", strings = [Manifest.permission.READ_CONTACTS]),
        Permission(alias = "notifications", strings = [Manifest.permission.POST_NOTIFICATIONS]),
    ],
)
class VoiceCommandPlugin : Plugin() {

    companion object {
        /** At or above this the call is carried out; below it the UI asks first. */
        private const val DEFAULT_THRESHOLD = 0.7
    }

    private lateinit var brain: Brain
    private lateinit var executor: Executor
    private lateinit var speaker: Speaker
    private var speech: SpeechListener? = null

    override fun load() {
        brain = Brain(context)
        executor = Executor(context)
        speaker = Speaker(context)
        Reminders.ensureChannel(context)
    }

    override fun handleOnDestroy() {
        speech?.cancel()
        speech = null
        speaker.release()
        brain.stop()
        super.handleOnDestroy()
    }

    // ----------------------------------------------------------------- status

    @PluginMethod
    fun getStatus(call: PluginCall) {
        val result = JSObject()
        result.put("enginePresent", brain.enginePresent)
        result.put("modelPresent", brain.modelPresent)
        result.put("modelBytes", if (brain.modelPresent) brain.weightsFile.length() else 0L)
        result.put("running", brain.isRunning)
        result.put("port", brain.port)
        result.put("lastError", brain.lastError ?: JSONObject.NULL)
        result.put("engineLog", brain.engineLog.takeLast(1500))
        result.put("onDeviceSpeech", Capabilities.onDeviceRecognition(context))

        val pending = JSArray()
        Reminders.pending(context).forEach { pending.put(it.text) }
        result.put("pendingReminders", pending)
        call.resolve(result)
    }

    @PluginMethod
    fun getCapabilities(call: PluginCall) {
        val result = JSObject()
        Capabilities.report(context).forEach { (key, value) -> result.put(key, value ?: JSONObject.NULL) }
        putPermissionStates(result)
        call.resolve(result)
    }

    /** Unpacks the model and starts the engine. Slow on the first run; call it once. */
    @PluginMethod
    fun prepare(call: PluginCall) {
        val problem = brain.start()
        if (problem != null) {
            call.reject(problem)
            return
        }
        call.resolve(JSObject().put("running", true).put("port", brain.port))
    }

    // ------------------------------------------------------------------ brain

    @PluginMethod
    fun ask(call: PluginCall) {
        val input = call.getString("input")?.trim()
        if (input.isNullOrEmpty()) {
            call.reject("Nothing to send.")
            return
        }
        engineProblem()?.let {
            call.reject(it)
            return
        }
        call.resolve(JSObject().put("reply", brain.ask(input).raw))
    }

    /**
     * The whole loop for one utterance: ask, gate on confidence, act if warranted.
     * `decision` is "act", "confirm" or "refuse" so the UI knows what to show.
     */
    @PluginMethod
    fun runCommand(call: PluginCall) {
        val input = call.getString("input")?.trim()
        if (input.isNullOrEmpty()) {
            call.reject("Nothing to send.")
            return
        }
        // Read through `data` so a missing or differently-typed value cannot throw.
        val threshold = call.data.optDouble("threshold", DEFAULT_THRESHOLD)
        val autoSpeak = call.data.optBoolean("speak", true)

        engineProblem()?.let {
            call.reject(it)
            return
        }

        val reply = brain.ask(input)
        val result = JSObject()
        result.put("reply", reply.raw)
        result.put("confidence", reply.confidence ?: JSONObject.NULL)
        result.put("reasoning", reply.reasoning ?: JSONObject.NULL)

        // A negative, reported or hypothetical phrasing never acts on the model's score
        // alone. See Guard for why this gate exists at all.
        val guard = Guard.reasonToConfirm(input)
        result.put("guard", guard ?: JSONObject.NULL)

        val confident = reply.calls.isNotEmpty() && (reply.confidence ?: 0.0) >= threshold
        val decision = when {
            confident && guard == null -> "act"
            reply.calls.isNotEmpty() || reply.held.isNotEmpty() -> "confirm"
            else -> "refuse"
        }
        result.put("decision", decision)

        when (decision) {
            "act" -> {
                val outcomes = reply.calls.map { executor.execute(it) }
                result.put("outcomes", outcomesToJs(outcomes))
                val spoken = outcomes.joinToString(" ") { it.spoken }
                result.put("spoken", spoken)
                if (autoSpeak && spoken.isNotBlank()) ui { speaker.say(spoken) }
            }
            "confirm" -> {
                val pending = if (reply.calls.isNotEmpty()) reply.calls else reply.held
                result.put("calls", callsToJs(pending))
                val spoken = guard ?: describeCalls(pending)
                result.put("spoken", spoken)
                if (autoSpeak) ui { speaker.say(spoken) }
            }
            else -> {
                val spoken = "I can't do that here."
                result.put("spoken", spoken)
                if (autoSpeak) ui { speaker.say(spoken) }
            }
        }

        call.resolve(result)
    }

    /** Runs calls the user has already confirmed in the UI. */
    @PluginMethod
    fun executeCalls(call: PluginCall) {
        val raw = call.getString("callsJson")
        if (raw.isNullOrEmpty()) {
            call.reject("No calls to run.")
            return
        }
        val calls = try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val item = array.optJSONObject(i) ?: return@mapNotNull null
                val name = item.optString("name")
                if (name.isEmpty()) null
                else ToolCall(name, item.optJSONObject("arguments") ?: JSONObject())
            }
        } catch (e: Exception) {
            call.reject("Could not read those calls: ${e.message}")
            return
        }
        if (calls.isEmpty()) {
            call.reject("No calls to run.")
            return
        }

        val outcomes = calls.map { executor.execute(it) }
        val spoken = outcomes.joinToString(" ") { it.spoken }
        if (call.data.optBoolean("speak", true)) ui { speaker.say(spoken) }
        call.resolve(JSObject().put("outcomes", outcomesToJs(outcomes)).put("spoken", spoken))
    }

    /** Starts the engine on demand and returns the reason it could not, or null. */
    private fun engineProblem(): String? = if (brain.isRunning) null else brain.start()

    // ----------------------------------------------------------------- speech

    @PluginMethod
    fun startListening(call: PluginCall) {
        if (getPermissionState("microphone") != PermissionState.GRANTED) {
            requestPermissionForAlias("microphone", call, "microphoneCallback")
            return
        }
        beginListening(call)
    }

    @PermissionCallback
    private fun microphoneCallback(call: PluginCall) {
        if (getPermissionState("microphone") != PermissionState.GRANTED) {
            call.reject("Microphone permission is needed to hear a command.")
            return
        }
        beginListening(call)
    }

    /** SpeechRecognizer is main-thread only, so creation and start are posted to the UI. */
    private fun beginListening(call: PluginCall) {
        ui {
            val listener = SpeechListener(
                context = context,
                onPartial = { text -> notifyListeners("speechPartial", JSObject().put("text", text)) },
                onFinal = { text -> notifyListeners("speechFinal", JSObject().put("text", text)) },
                onError = { message -> notifyListeners("speechError", JSObject().put("message", message)) },
                onListeningChanged = { listening ->
                    notifyListeners("speechState", JSObject().put("listening", listening))
                },
            )
            speech?.cancel()
            speech = listener
            if (listener.start()) {
                call.resolve(JSObject().put("listening", true).put("onDevice", listener.onDevice))
            } else {
                call.reject("Could not start listening.")
            }
        }
    }

    @PluginMethod
    fun stopListening(call: PluginCall) {
        ui { speech?.stop() }
        call.resolve()
    }

    @PluginMethod
    fun cancelListening(call: PluginCall) {
        ui { speech?.cancel() }
        notifyListeners("speechState", JSObject().put("listening", false))
        call.resolve()
    }

    // -------------------------------------------------------------------- tts

    @PluginMethod
    fun speak(call: PluginCall) {
        val text = call.getString("text")?.trim()
        if (text.isNullOrEmpty()) {
            call.reject("Nothing to say.")
            return
        }
        ui { speaker.say(text) }
        call.resolve()
    }

    @PluginMethod
    fun stopSpeaking(call: PluginCall) {
        ui { speaker.stop() }
        call.resolve()
    }

    // ------------------------------------------------------------ permissions

    /**
     * Named getPermissionStates rather than checkPermissions: Capacitor's Plugin base
     * class already declares checkPermissions, so a method of that name would have to
     * override it instead.
     */
    @PluginMethod
    fun getPermissionStates(call: PluginCall) {
        val result = JSObject()
        putPermissionStates(result)
        call.resolve(result)
    }

    @PluginMethod
    fun requestPermission(call: PluginCall) {
        val alias = call.getString("alias")
        // The null check is first so that `alias` smart-casts to String below; a bare
        // `!in` test leaves it nullable as far as the compiler is concerned.
        if (alias == null || alias !in listOf("microphone", "contacts", "notifications")) {
            call.reject("Unknown permission \"$alias\".")
            return
        }
        requestPermissionForAlias(alias, call, "permissionCallback")
    }

    @PermissionCallback
    private fun permissionCallback(call: PluginCall) {
        val result = JSObject()
        putPermissionStates(result)
        call.resolve(result)
    }

    private fun putPermissionStates(target: JSObject) {
        target.put("permissionMicrophone", getPermissionState("microphone").toString())
        target.put("permissionContacts", getPermissionState("contacts").toString())
        // POST_NOTIFICATIONS does not exist before Android 13, and querying it there
        // reports "denied" even though notifications work fine.
        target.put(
            "permissionNotifications",
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                getPermissionState("notifications").toString()
            } else {
                PermissionState.GRANTED.toString()
            },
        )
    }

    // -------------------------------------------------------------- reminders

    @PluginMethod
    fun listReminders(call: PluginCall) {
        val array = JSArray()
        Reminders.pending(context).sortedBy { it.at }.forEach { reminder ->
            array.put(
                JSObject()
                    .put("id", reminder.id)
                    .put("at", reminder.at)
                    .put("text", reminder.text),
            )
        }
        call.resolve(JSObject().put("reminders", array))
    }

    @PluginMethod
    fun cancelReminder(call: PluginCall) {
        val id = call.data.optInt("id", -1)
        if (id < 0) {
            call.reject("Which reminder?")
            return
        }
        Reminders.cancel(context, id)
        call.resolve()
    }

    // --------------------------------------------------------------- settings

    @PluginMethod
    fun openAppSettings(call: PluginCall) {
        startSafely(call, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    /** Android 12+ gates exact alarms behind a per-app toggle. */
    @PluginMethod
    fun openExactAlarmSettings(call: PluginCall) {
        val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM
        } else {
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS
        }
        startSafely(call, Intent(action).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun startSafely(call: PluginCall, intent: Intent) {
        try {
            context.startActivity(intent)
            call.resolve()
        } catch (e: Exception) {
            call.reject("Could not open settings: ${e.message}")
        }
    }

    // ----------------------------------------------------------------- helpers

    private fun ui(block: () -> Unit) {
        val activity = activity
        if (activity == null || Looper.myLooper() == Looper.getMainLooper()) block()
        else activity.runOnUiThread(block)
    }

    private fun outcomesToJs(outcomes: List<Executor.Outcome>): JSArray {
        val array = JSArray()
        outcomes.forEach { outcome ->
            array.put(
                JSObject()
                    .put("tool", outcome.tool)
                    .put("tier", outcome.tier.wire)
                    .put("ok", outcome.ok)
                    .put("spoken", outcome.spoken)
                    .put("detail", outcome.detail ?: JSONObject.NULL),
            )
        }
        return array
    }

    private fun callsToJs(calls: List<ToolCall>): JSArray {
        val array = JSArray()
        calls.forEach {
            array.put(JSObject().put("name", it.name).put("arguments", it.arguments.toString()))
        }
        return array
    }

    /** A plain-language read-back for a call the UI wants confirmed. */
    private fun describeCalls(calls: List<ToolCall>): String =
        calls.joinToString(" and ") { call ->
            val args = call.arguments
            when (call.name) {
                "set_wifi" -> "Turn Wi-Fi ${args.optString("action")}"
                "set_hotspot" -> "Turn the hotspot ${args.optString("action")}"
                "set_torch" -> "Turn the flashlight ${args.optString("action")}"
                "send_sms" -> "Message ${args.optString("recipient")} saying ${args.optString("message")}"
                "set_reminder" -> "Remind you ${args.optString("when")} to ${args.optString("text")}"
                else -> call.name
            }
        } + "?"
}

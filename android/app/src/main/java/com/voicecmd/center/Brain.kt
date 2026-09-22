package com.voicecmd.center

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One tool call the model produced. `arguments` is grammar-guaranteed to match the schema. */
data class ToolCall(val name: String, val arguments: JSONObject)

/**
 * A turn from the engine. Mirrors the documented response shape: `function_calls` holds
 * the calls to run, `suppressed_calls` holds a call the engine withheld, and an empty
 * `function_calls` with nothing held is an outright refusal.
 *
 * `negated` and `ungrounded` come from the engine's own `validation` block. They matter
 * more than they look: the engine tells us when the request was negative, and when an
 * argument was invented rather than copied from what the user actually said. Both are
 * exactly the cases where acting without asking would be wrong.
 */
data class Reply(
    val type: String?,
    val calls: List<ToolCall>,
    val held: List<ToolCall>,
    val reasoning: String?,
    val confidence: Double?,
    val negated: Boolean,
    val ungrounded: List<String>,
    val raw: String,
) {
    val isRefusal: Boolean get() = calls.isEmpty() && held.isEmpty()
}

/**
 * Owns the Needle 3 engine.
 *
 * The engine ships as a self-contained arm64 executable that loads needle3.cact. Running
 * it as a child process in `--serve` mode means the 33.7 MB model is read once at start
 * and every later command is a loopback POST, which keeps per-command latency low and
 * avoids writing and maintaining a JNI shim against libneedle.a.
 *
 * The trade-off is that the engine listens on a TCP port inside the device. It is bound
 * to loopback by the engine's own default and this app only ever talks to 127.0.0.1, but
 * see README.md > Known gaps: if a future engine build binds 0.0.0.0 this needs revisiting.
 */
class Brain(private val ctx: Context) {

    companion object {
        private const val TAG = "Brain"
        private const val PORT_RANGE_START = 8399
        private const val PORT_RANGE_END = 8409
        private const val MODEL_ASSET = "needle3.cact"
        private const val TOOLS_ASSET = "tools.json"
    }

    @Volatile
    var port: Int = -1
        private set

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    private var process: Process? = null

    private var drainThread: Thread? = null

    /** Tail of engine output, kept for diagnostics surfaced in the UI. */
    @Volatile
    var engineLog: String = ""
        private set

    val engineFile: File
        get() = File(ctx.applicationInfo.nativeLibraryDir, "libneedle_engine.so")

    val weightsFile: File get() = File(ctx.filesDir, MODEL_ASSET)
    val toolsFile: File get() = File(ctx.filesDir, TOOLS_ASSET)
    val systemFile: File get() = File(ctx.filesDir, "system.txt")

    val enginePresent: Boolean get() = engineFile.isFile

    /**
     * The model unpacked into the app's own storage. This is the only path that may be
     * handed to the engine, so stageAssets must gate on exactly this and nothing looser.
     */
    private val modelExtracted: Boolean
        get() = weightsFile.isFile && weightsFile.length() > 1024 * 1024

    /**
     * Whether the model is usable at all: either already unpacked, or still bundled in the
     * APK waiting to be. The UI asks this question.
     *
     * Keep this separate from modelExtracted. Conflating the two is a trap this code has
     * already fallen into once: gating the unpack on "is the model available anywhere"
     * makes it skip the unpack, because the model bundled in the APK counts as available,
     * and the engine is then handed a path that does not exist. It fails to start, and
     * every command silently does nothing.
     */
    val modelPresent: Boolean get() = modelExtracted || modelBundled

    /** Whether needle3.cact is inside the APK's assets. Read once. */
    private val modelBundled: Boolean by lazy {
        try {
            // openFd works because the asset is stored uncompressed (noCompress 'cact').
            ctx.assets.openFd(MODEL_ASSET).use { it.length > 1024 * 1024 }
        } catch (_: Exception) {
            false
        }
    }
    val isRunning: Boolean get() = process?.isAlive == true && port > 0

    /** Copies the bundled model and schema out of the APK once. */
    fun stageAssets(): String? {
        if (!enginePresent) return "This build has no Needle engine for this device's CPU (needs arm64-v8a)."
        try {
            if (!modelExtracted) {
                Log.i(TAG, "extracting $MODEL_ASSET to ${weightsFile.absolutePath}")
                ctx.assets.open(MODEL_ASSET).use { input ->
                    File(ctx.filesDir, "$MODEL_ASSET.part").outputStream().use { output ->
                        input.copyTo(output, 1 shl 16)
                    }
                }
                File(ctx.filesDir, "$MODEL_ASSET.part").renameTo(weightsFile)
            }
            ctx.assets.open(TOOLS_ASSET).use { input ->
                toolsFile.outputStream().use { output -> input.copyTo(output) }
            }
            engineFile.setExecutable(true, false)
            return null
        } catch (e: Exception) {
            return "Could not unpack the model: ${e.message}"
        }
    }

    /**
     * Session facts, never instructions. The model resolves relative phrases like
     * "tomorrow at 7" against `date:` and passes the phrase through, which is why
     * TimePhrases does the arithmetic on this side using the same clock.
     */
    private fun writeSystemFacts() {
        val stamp = SimpleDateFormat("yyyy-MM-dd EEE HH:mm", Locale.US).format(Date())
        val battery = Capabilities.batteryPercent(ctx)?.let { "$it%" } ?: "unknown"
        val facts = buildString {
            append("date: ").append(stamp).append("; ")
            append("locale: ").append(Locale.getDefault().toString()).append("; ")
            append("device: phone; ")
            append("battery: ").append(battery).append("; ")
            append("network: ").append(Capabilities.networkFact(ctx))
        }
        systemFile.writeText(facts + "\n")
    }

    private fun freePort(): Int {
        for (candidate in PORT_RANGE_START..PORT_RANGE_END) {
            try {
                ServerSocket(candidate).use { return candidate }
            } catch (_: IOException) {
                // in use, try the next one
            }
        }
        return PORT_RANGE_START
    }

    /** Starts the engine if it is not already serving. Returns null on success. */
    @Synchronized
    fun start(): String? {
        if (isRunning) return null
        lastError = null
        val stageProblem = stageAssets()
        if (stageProblem != null) {
            lastError = stageProblem
            return stageProblem
        }
        // Refuse to start without a model file on disk. Without this the engine is launched
        // with a path that does not exist, dies immediately, and every command does nothing
        // with no explanation — which is exactly what happened when the extraction was
        // skipped once. A loud failure here is far better than a silent one later.
        if (!modelExtracted) {
            val message = "The voice model is not unpacked in the app's storage and could not be unpacked from the APK."
            lastError = message
            return message
        }
        writeSystemFacts()

        port = freePort()
        return try {
            val pb = ProcessBuilder(
                engineFile.absolutePath,
                "--model", weightsFile.absolutePath,
                "--tools", toolsFile.absolutePath,
                "--system", systemFile.absolutePath,
                "--serve",
                "--port", port.toString(),
            )
            pb.directory(ctx.filesDir)
            pb.redirectErrorStream(true)
            val p = pb.start()
            process = p

            // The engine logs to stdout. Leaving that pipe undrained fills the buffer and
            // blocks it mid-command, so drain on a daemon thread and keep the tail.
            drainThread = Thread {
                try {
                    p.inputStream.bufferedReader().forEachLine { line ->
                        engineLog = (engineLog + line + "\n").takeLast(4000)
                    }
                } catch (_: Exception) {
                    // process ended
                }
            }.apply {
                isDaemon = true
                name = "needle-engine-drain"
                start()
            }

            val deadline = System.currentTimeMillis() + 120_000
            while (System.currentTimeMillis() < deadline) {
                if (!p.isAlive) {
                    val message = "The Needle engine stopped during start-up: ${engineLog.takeLast(400).trim()}"
                    lastError = message
                    return message
                }
                try {
                    post("/reset", null)
                    Log.i(TAG, "engine ready on 127.0.0.1:$port")
                    return null
                } catch (_: Exception) {
                    Thread.sleep(400)
                }
            }
            lastError = "The Needle engine did not start within two minutes."
            lastError
        } catch (e: Exception) {
            lastError = "Could not start the Needle engine: ${e.message}"
            lastError
        }
    }

    @Synchronized
    fun stop() {
        try {
            process?.destroy()
            process?.let { if (!it.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) it.destroyForcibly() }
        } catch (_: Exception) {
            // nothing useful to do while shutting down
        }
        process = null
        drainThread = null
        port = -1
    }

    private fun post(path: String, body: String?): String {
        val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 5_000
            connection.readTimeout = 90_000
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            } else {
                connection.setFixedLengthStreamingMode(0)
                connection.doOutput = true
                connection.outputStream.use { /* empty body */ }
            }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (connection.responseCode !in 200..299) {
                throw IOException("HTTP ${connection.responseCode} from the engine: ${text.take(200)}")
            }
            return text
        } finally {
            connection.disconnect()
        }
    }

    fun reset() {
        if (!isRunning) return
        try {
            post("/reset", null)
        } catch (e: Exception) {
            Log.w(TAG, "reset failed: ${e.message}")
        }
    }

    /** Sends one utterance to the model and returns the parsed turn. */
    fun ask(input: String): Reply {
        if (!isRunning) {
            start()?.let {
                return Reply(null, emptyList(), emptyList(), null, null, false, emptyList(), """{"error":"$it"}""")
            }
        }
        val payload = JSONObject().put("input", input).toString()
        val text = post("/complete", payload)
        val json = extractJson(text)
            ?: return Reply(null, emptyList(), emptyList(), null, null, false, emptyList(), text)

        // The engine reports its own grounding and negation checks under "validation".
        val validation = json.optJSONObject("validation")
        val ungrounded = ArrayList<String>()
        validation?.optJSONArray("ungrounded")?.let { array ->
            for (i in 0 until array.length()) {
                array.optString(i).takeIf { it.isNotEmpty() }?.let { ungrounded.add(it) }
            }
        }

        return Reply(
            type = json.optString("type").ifEmpty { null },
            calls = parseCalls(json.optJSONArray("function_calls")),
            held = parseCalls(json.optJSONArray("suppressed_calls")),
            reasoning = json.optString("reasoning").ifEmpty { null },
            confidence = if (json.has("confidence") && !json.isNull("confidence")) json.optDouble("confidence") else null,
            negated = validation?.optBoolean("negation", false) ?: false,
            ungrounded = ungrounded,
            raw = text,
        )
    }

    private fun parseCalls(array: JSONArray?): List<ToolCall> {
        if (array == null) return emptyList()
        val out = ArrayList<ToolCall>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val name = item.optString("name")
            if (name.isEmpty()) continue
            out.add(ToolCall(name, item.optJSONObject("arguments") ?: JSONObject()))
        }
        return out
    }

    /** The engine may frame its JSON with log lines; take the outermost object. */
    private fun extractJson(text: String): JSONObject? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            JSONObject(text.substring(start, end + 1))
        } catch (_: Exception) {
            null
        }
    }
}

# Voice Command Center

An offline voice command app for Android. You speak, and it works the phone: Wi-Fi and
hotspot, a text message, a reminder, the flashlight.

Understanding runs on the device. [Needle 3](https://huggingface.co/Cactus-Compute/needle3)
picks which tool to call and fills its arguments; nothing about your command leaves the
phone.

---

## The fifteen commands

| Say | Tool | Tier | What really happens |
| --- | --- | --- | --- |
| "Turn on the flashlight" | `set_torch` | **silent** | `CameraManager.setTorchMode`. No permission needed. |
| "Mute the phone", "make it louder" | `set_volume` | **silent** | `AudioManager`. Media, ringer, alarm or notification stream. |
| "Remind me at 5 to mark the scripts" | `set_reminder` | **silent** | `AlarmManager`, exact when exact alarms are allowed, otherwise a few minutes late and it says so. |
| "Set an alarm for 6 am" | `set_alarm` | **silent** | Handed to the clock app, so it snoozes and rings full-screen. |
| "Set a timer for 10 minutes" | `set_timer` | **silent** | Also the clock app's, which is what a timer should be. |
| "Read my last messages" | `read_messages` | **silent** | Reads the inbox aloud, naming who each message is from. Needs the Messages permission. |
| "Answer the call" | `answer_call` | **silent** | `TelecomManager.acceptRingingCall`, or `endCall` to hang up. Needs the Phone permission. |
| "Text dad that I'll be late" | `send_sms` | **confirm** | `ACTION_SENDTO` opens your messaging app with the body written. You tap send. |
| "Call mum" | `dial_contact` | **confirm** | `ACTION_DIAL` opens the dialler with the number ready. You press call. |
| "Open WhatsApp" | `open_app` | **panel** | Launches the app by the name you said. |
| "Open Bluetooth settings" | `open_settings` | **panel** | Opens that settings screen for you to change by hand. |
| "Navigate to Kampala Road" | `navigate_to` | **panel** | Turn-by-turn if a maps app answers, otherwise a map pin and you tap Directions. |
| "Search for the news in Uganda" | `search_web` | **panel** | Opens your browser. **Needs a data connection** — this is the one hand-off that leaves the phone's own abilities. |
| "Turn off Wi-Fi" | `set_wifi` | **panel** | Opens the internet panel. On Android 10+ `setWifiEnabled` is a no-op for apps. A root shell makes it silent. |
| "Turn on the hotspot" | `set_hotspot` | **panel** | Tethering is an `@SystemApi`. No app can toggle it. The panel is the real answer. |

The badge on each result card shows which tier ran, so the app never implies it did
something it only opened a panel for.

**Two commands from the usual lists are deliberately absent.** Weather and translation need
either the internet or a second model on the device, and this app's whole premise is that
understanding happens offline. "What is the weather" therefore opens a web search, which is
honest about needing data, rather than pretending to answer.

**Needle 3 is a brain, not an ear.** It is a 8–29 MB tool-calling model: it takes text and
returns tool calls. It does not transcribe speech. Speech-to-text is a separate stage —
Android's on-device recogniser (`SpeechRecognizer.createOnDeviceSpeechRecognizer`), which
needs no bundled model. So the 29 MB figure is the understanding layer, not the whole app.

**Fifteen tools means retrieval, not a flat list.** The engine renders five tools directly;
above that it embeds every schema and puts only the five closest in front of the model for
each turn. That is the supported path for a catalogue this size, but it means a tool the
retriever does not surface is unreachable rather than merely unlikely. `validate-schema.mjs`
warns whenever the count crosses five so the trade-off stays visible.

---

## How a command flows

```
  speech (on-device ASR)
        |
        v
  Needle 3  --tools.json-->  {"function_calls":[{"name":"set_wifi","arguments":{"action":"off"}}],
                              "confidence":0.94, "reasoning":"'off' -> action"}
        |
        v
  confidence gate            >= 0.70  act
                             <  0.70  confirm with the user
                             empty    refuse ("I can't do that here")
        |
        v
  Executor                  best tier this device allows
        |
        v
  outcome + spoken reply
```

`function_calls` empty is a refusal — Needle generates no free text, so there is no
fallback to invent. That maps onto Android neatly: act when the model is sure and the
platform allows it, confirm when the user still has a tap to make, refuse otherwise.

---

## Layout

```
shared/tools.json           the 5 tool schemas — the single source of truth
shared/cases.json           34 frozen routing cases in six categories
tools/validate-schema.mjs   offline gate: schema/case drift, enum limits, tool count
tools/run-cases.mjs         the same suite against the real engine (used in CI)
scripts/fetch-models.mjs    downloads weights + engines, stages the Android engine
www/                        the UI (plain HTML/CSS/JS, no bundler)
android/                    Capacitor 7 project + the Kotlin plugin
  app/src/main/java/com/voicecmd/center/
    VoiceCommandPlugin.kt   the Capacitor bridge
    Brain.kt                Needle engine process + loopback client
    Executor.kt             capability-tiered tool execution
    Capabilities.kt         what this device actually permits
    Contacts.kt             spoken name -> number (kept out of the model on purpose)
    TimePhrases.kt          "at 5 PM", "tomorrow at 8:30" -> an instant
    Reminders.kt            alarms, notification, reboot recovery
    SpeechListener.kt       on-device recognition
    Speaker.kt              spoken replies
```

### Why the tool set is exactly five

The engine renders five or fewer tools directly. Above five it embeds every schema and
puts only the five closest in the per-turn prefix — and an unselected tool becomes
*unreachable*, not merely unlikely. Polarity is a single `action: ["on", "off"]` enum
rather than separate on/off tools, which keeps the set at five. `tools/validate-schema.mjs`
fails the build if that number creeps up.

### Why contacts are resolved in Kotlin

Tool schemas share the context window with the conversation. Listing contacts in the
prompt would blow that budget, so `recipient` stays a plain span of what you said and
`Contacts.kt` does the lookup afterwards.

---

## Build

The APK is built by GitHub Actions, so no local Android SDK or JDK is needed. Push to
`main`, or run the workflow by hand:

```
gh workflow run build-apk.yml
```

Then download `voice-command-center-apk` from the run's artifacts.

### What CI does, in order

1. `tools/validate-schema.mjs` — fast gate, no model needed.
2. `scripts/fetch-models.mjs` — downloads `needle3.cact` (33.7 MB) plus the
   `android-arm64` and `linux-x86_64` engines. Cached between runs.
3. `tools/run-cases.mjs` — runs the 34 cases against the **real** model.
4. `npx cap sync android` + `./gradlew assembleDebug`.
5. Uploads the APK, **then** fails the run if the routing suite failed. That ordering is
   deliberate: a red run still leaves you an APK to test on the phone.

### Install over ADB

```sh
adb install -r app-debug.apk
adb logcat -s Brain VoiceCommand Capacitor
```

First launch unpacks the 33.7 MB model out of the APK, which takes a few seconds.

---

## Run the tests locally

```sh
npm install
node tools/validate-schema.mjs                     # no model required
node scripts/fetch-models.mjs --platform windows-x86_64
node tools/run-cases.mjs --engine models/windows-x86_64/needle.exe
```

`run-cases.mjs` reports per-category pass counts, each case's confidence, and which cases
would fall below the act/confirm threshold — which is how you tune that threshold against
evidence instead of guessing.

---

## Permissions

| Permission | Why | When |
| --- | --- | --- |
| `RECORD_AUDIO` | hear the command | runtime prompt |
| `READ_CONTACTS` | turn "Head of Department" into a number | runtime prompt |
| `POST_NOTIFICATIONS` | show a due reminder | Android 13+ runtime prompt |
| `SCHEDULE_EXACT_ALARM` | fire a reminder on time | Android 12+ settings toggle |
| `INTERNET` | loopback only, to the local Needle engine | automatic |
| `ACCESS_WIFI_STATE`, `ACCESS_NETWORK_STATE` | read state for the spoken reply | automatic |
| `RECEIVE_BOOT_COMPLETED` | re-arm reminders after a restart | automatic |

`CHANGE_WIFI_STATE` is declared for the legacy silent toggle, which only applies below
Android 10.

---

## Known gaps

Things that are real and not yet handled, rather than hidden:

1. **The engine listens on a loopback TCP port.** `Brain` starts the bundled Needle binary
   with `--serve` and talks to it at `127.0.0.1`. This avoids a JNI shim and keeps the
   model loaded between commands, but the port is only as private as the engine's own bind
   address. `network_security_config.xml` permits cleartext to loopback only. **Verify the
   engine binds `127.0.0.1` and not `0.0.0.0`** before trusting this on an untrusted
   network. The alternative is linking `libneedle.a` through JNI, which is the hardened
   path and not yet done.
2. **The routing suite has never run against the real model here.** The sandbox that wrote
   this had an egress cap, so `needle3.cact` could not be downloaded. The suite, the
   schema and the validator are in place; the first CI run is what proves the schema. Expect
   to tune `shared/tools.json` wording from its output.
3. **No wake word.** Commands start from a tap. Always-on listening needs a foreground
   service with the `microphone` type and a keyword spotter, which is a further step.
4. **I could not compile the Kotlin.** No JDK or Android SDK is installed on the machine
   this was written on. The first CI run is also the first compile; expect a round of
   fix-ups.
5. **`--depth` is unused.** Needle can run a subnetwork of as few as 2 layers, which would
   cut latency and memory on slower phones. `Brain` always runs the full 20-layer model.
6. **Hotspot via root is opportunistic.** `cmd tethering` exists on some builds and not
   others; the executor tries it and falls back to the panel, but it is not dependable.

---

## Credits

Needle 3 is by [Cactus Compute](https://cactuscompute.com/needle) (Apache-2.0 on the
weights repo). The tool-design rules this schema follows — one tool per action, names
users would say, formats in descriptions, constraints in the grammar, five tools or fewer —
come from their [tool-design guide](https://cactuscompute.com/blog/designing-tools-for-needle)
and the [confidence guide](https://cactuscompute.com/blog/needle-confidence).

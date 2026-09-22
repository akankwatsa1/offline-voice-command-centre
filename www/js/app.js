/* Voice Command Center — web layer.
 *
 * No bundler: Capacitor injects window.Capacitor into the WebView, so the native plugin
 * is reachable directly. All decision-making that needs the phone lives in Kotlin; this
 * file only drives the UI and shows honestly what happened, including which capability
 * tier each action ended up using.
 */
(function () {
  "use strict";

  const VC =
    (window.Capacitor && window.Capacitor.Plugins && window.Capacitor.Plugins.VoiceCommand) || null;

  const el = (id) => document.getElementById(id);
  const statusEl = el("status");
  const micEl = el("mic");
  const micLabel = el("mic-label");
  const transcriptEl = el("transcript");
  const resultEl = el("result");
  const confirmBar = el("confirm-bar");
  const confirmText = el("confirm-text");
  const logEl = el("log");
  const remindersEl = el("reminders");

  /** Calls awaiting a tap on "Do it". */
  let pendingCalls = null;
  let listening = false;
  let ready = false;

  const TIER_LABEL = {
    silent: "done",
    panel: "needs a tap",
    confirm: "confirm",
    unsupported: "not possible",
  };

  function setStatus(text) {
    statusEl.textContent = text;
  }

  function appendLog(line) {
    const stamp = new Date().toLocaleTimeString();
    logEl.textContent += `[${stamp}] ${line}\n`;
    logEl.scrollTop = logEl.scrollHeight;
  }

  function clear(node) {
    while (node.firstChild) node.removeChild(node.firstChild);
  }

  function para(text, className) {
    const p = document.createElement("p");
    if (className) p.className = className;
    p.textContent = text;
    return p;
  }

  // ------------------------------------------------------------ rendering

  function renderOutcomes(outcomes) {
    clear(resultEl);
    outcomes.forEach((o) => {
      const box = document.createElement("div");
      box.className = "outcome";

      const head = document.createElement("div");
      const tier = document.createElement("span");
      tier.className = "tier " + (o.tier || "unsupported");
      tier.textContent = TIER_LABEL[o.tier] || o.tier;
      const tool = document.createElement("span");
      tool.className = "tool";
      tool.textContent = " " + o.tool;
      head.appendChild(tier);
      head.appendChild(tool);
      box.appendChild(head);

      box.appendChild(para(o.spoken, "spoken"));
      if (o.detail) box.appendChild(para(o.detail, "detail"));
      resultEl.appendChild(box);
    });
  }

  function renderDecision(decision, reply, confidence, reasoning) {
    clear(resultEl);
    const badge = document.createElement("span");
    badge.className = "badge " + decision;
    badge.textContent =
      decision === "act" ? "carried out" : decision === "confirm" ? "needs your ok" : "can't do that";
    resultEl.appendChild(badge);

    const bits = [];
    if (typeof confidence === "number") bits.push(`confidence ${confidence.toFixed(2)}`);
    const called = (reply && reply.function_calls) || [];
    if (called.length) bits.push(`tools: ${called.map((c) => c.name).join(", ")}`);
    if (bits.length) resultEl.appendChild(para(bits.join(" · "), "muted"));
    if (reasoning) resultEl.appendChild(para(reasoning, "detail"));
  }

  function showConfirm(calls, spoken) {
    pendingCalls = calls;
    confirmText.textContent = spoken || "Run this?";
    confirmBar.hidden = false;
    el("confirm-run").focus();
  }

  function hideConfirm() {
    pendingCalls = null;
    confirmBar.hidden = true;
  }

  /**
   * Offers a list of people when a spoken name matched more than one contact.
   *
   * The app never picks for the user here: "call mama" with three Mamas in the address
   * book is a question only they can answer, and a wrong guess is one they cannot see in
   * order to correct. The names are spoken by the native side as well, so the list works
   * without looking at the screen.
   */
  function showChoices(pendingCall, choices) {
    const box = document.createElement("div");
    box.className = "confirm-bar";
    box.setAttribute("role", "group");
    box.setAttribute("aria-label", "Choose a contact");

    const label = document.createElement("p");
    label.className = "confirm-text";
    label.textContent = "Which one?";
    box.appendChild(label);

    const row = document.createElement("div");
    row.className = "confirm-actions";
    row.style.flexWrap = "wrap";

    choices.forEach((contact, index) => {
      const button = document.createElement("button");
      button.className = "btn";
      button.textContent = `${index + 1}. ${contact.name}`;
      button.setAttribute("aria-label", `Use ${contact.name} on ${contact.number}`);
      button.addEventListener("click", () => runChoice(pendingCall, contact));
      row.appendChild(button);
    });

    box.appendChild(row);
    resultEl.appendChild(box);
    const first = row.querySelector("button");
    if (first) first.focus();
  }

  async function runChoice(pendingCall, chosen) {
    if (!VC || !pendingCall) return;
    let args;
    try {
      args = JSON.parse(pendingCall.arguments || "{}");
    } catch (_) {
      args = {};
    }
    // Substitute the number the user chose for the name they said.
    args.recipient = chosen.number;
    setStatus(`Using ${chosen.name}`);
    try {
      const res = await VC.executeCalls({
        callsJson: JSON.stringify([{ name: pendingCall.name, arguments: args }]),
        speak: true,
      });
      renderOutcomes(res.outcomes || []);
      setStatus("Done");
    } catch (e) {
      setStatus("Failed");
      appendLog("choice failed: " + ((e && e.message) || e));
    }
  }

  // -------------------------------------------------------------- command

  async function runCommand(text) {
    if (!VC) return;
    if (!text || !text.trim()) return;
    hideConfirm();
    transcriptEl.textContent = text;
    setStatus("Thinking…");

    let res;
    try {
      res = await VC.runCommand({ input: text.trim(), speak: true });
    } catch (e) {
      setStatus("Engine problem");
      renderDecision("refuse", null, null, String((e && e.message) || e));
      appendLog("runCommand failed: " + ((e && e.message) || e));
      return;
    }

    let reply = null;
    try {
      reply = JSON.parse(res.reply || "{}");
    } catch (_) {
      /* keep null */
    }
    appendLog("model: " + (res.reply || "").slice(0, 400));

    if (res.decision === "act") {
      renderDecision("act", reply, res.confidence, res.reasoning);
      (res.outcomes || []).forEach((o) => {
        const box = document.createElement("div");
        box.className = "outcome";
        const tier = document.createElement("span");
        tier.className = "tier " + (o.tier || "unsupported");
        tier.textContent = TIER_LABEL[o.tier] || o.tier;
        const tool = document.createElement("span");
        tool.className = "tool";
        tool.textContent = " " + o.tool;
        box.appendChild(tier);
        box.appendChild(tool);
        box.appendChild(para(o.spoken, "spoken"));
        if (o.detail) box.appendChild(para(o.detail, "detail"));
        resultEl.appendChild(box);
      });
      setStatus("Done");
      refreshReminders();
    } else if (res.decision === "choose") {
      renderDecision("confirm", reply, res.confidence, res.reasoning);
      showChoices(res.pendingCall, res.choices || []);
      setStatus("Choose a contact");
    } else if (res.decision === "confirm") {
      renderDecision("confirm", reply, res.confidence, res.reasoning);
      showConfirm(res.calls || [], res.spoken);
      setStatus("Waiting for your ok");
    } else {
      renderDecision("refuse", reply, res.confidence, res.reasoning);
      setStatus("No tool covers that");
    }
  }

  async function runPending() {
    if (!VC || !pendingCalls) return;
    const callsJson = JSON.stringify(
      pendingCalls.map((c) => ({
        name: c.name,
        arguments: typeof c.arguments === "string" ? JSON.parse(c.arguments || "{}") : c.arguments || {},
      })),
    );
    hideConfirm();
    setStatus("Doing it…");
    try {
      const res = await VC.executeCalls({ callsJson, speak: true });
      renderOutcomes(res.outcomes || []);
      setStatus("Done");
      refreshReminders();
    } catch (e) {
      setStatus("Failed");
      appendLog("executeCalls failed: " + ((e && e.message) || e));
    }
  }

  // --------------------------------------------------------------- speech

  async function toggleMic() {
    if (!VC) return;
    if (listening) {
      await VC.stopListening();
      return;
    }
    // Tapping to talk should cut off whatever the app was saying.
    if (VC.stopSpeaking) await VC.stopSpeaking().catch(() => {});
    hideConfirm();
    try {
      const res = await VC.startListening();
      if (res && res.onDevice === false) {
        appendLog("note: this phone has no on-device speech model, so the platform recogniser is used");
      }
    } catch (e) {
      setStatus("Mic unavailable");
      appendLog("startListening failed: " + ((e && e.message) || e));
    }
  }

  function wireSpeechEvents() {
    if (!VC || !VC.addListener) return;
    VC.addListener("speechPartial", (d) => {
      transcriptEl.textContent = d.text || "";
    });
    VC.addListener("speechFinal", (d) => {
      transcriptEl.textContent = d.text || "";
      runCommand(d.text || "");
    });
    VC.addListener("speechError", (d) => {
      setStatus(d.message || "Speech error");
      appendLog("speech: " + (d.message || ""));
    });
    VC.addListener("speechState", (d) => {
      listening = !!d.listening;
      micEl.setAttribute("aria-pressed", String(listening));
      micLabel.textContent = listening ? "Listening — tap to stop" : "Tap to speak";
      setStatus(listening ? "Listening…" : ready ? "Ready" : "Not ready");
    });
  }

  // ---------------------------------------------------------------- setup

  function renderPermissions(p) {
    const set = (name, value) => {
      const node = el("perm-" + name);
      if (node) node.textContent = value || "unknown";
    };
    set("microphone", p.permissionMicrophone);
    set("contacts", p.permissionContacts);
    set("sms", p.permissionSms);
    set("phone", p.permissionPhone);
    set("notifications", p.permissionNotifications);
  }

  async function refreshCapabilities() {
    if (!VC) return;
    const caps = await VC.getCapabilities();
    renderPermissions(caps);

    const dl = el("caps");
    clear(dl);
    const rows = [
      ["Phone", `${caps.device} · Android ${caps.androidRelease} (API ${caps.sdkInt})`],
      ["Wi-Fi", caps.wifi],
      ["Hotspot", caps.hotspot],
      ["Flashlight", caps.torch],
      ["Text message", caps.sms],
      ["Reminders", caps.reminderApproximate ? "silent, maybe a few minutes late" : "silent and exact"],
      ["Root shell", caps.rooted ? "yes" : "no"],
      ["Device owner", caps.deviceOwner ? "yes" : "no"],
    ];
    rows.forEach(([k, v]) => {
      const dt = document.createElement("dt");
      dt.textContent = k;
      const dd = document.createElement("dd");
      dd.textContent = String(v);
      dl.appendChild(dt);
      dl.appendChild(dd);
    });

    el("cap-summary").textContent =
      caps.wifi === "silent"
        ? "This phone lets the app change Wi-Fi on its own."
        : "This phone does not let an app change Wi-Fi or the hotspot on its own — the app opens the exact panel and you tap once.";

    el("offline-note").textContent = caps.onDeviceRecognition
      ? "Speech is transcribed on this device, and commands are understood by the bundled Needle 3 model. Nothing is uploaded."
      : "This phone has no on-device speech model, so the platform recogniser may use the network for the transcript. Everything after that — understanding and doing — stays on the device.";
  }

  async function refreshReminders() {
    if (!VC) return;
    try {
      const res = await VC.listReminders();
      const list = res.reminders || [];
      clear(remindersEl);
      if (!list.length) {
        const li = document.createElement("li");
        li.className = "muted";
        li.textContent = "None set.";
        remindersEl.appendChild(li);
        return;
      }
      list.forEach((r) => {
        const li = document.createElement("li");
        const span = document.createElement("span");
        span.textContent = r.text;
        const time = document.createElement("time");
        time.dateTime = new Date(r.at).toISOString();
        time.textContent = new Date(r.at).toLocaleString();
        const btn = document.createElement("button");
        btn.className = "btn small";
        btn.textContent = "Cancel";
        // A list of buttons all reading "Cancel" tells a screen reader user nothing about
        // which reminder they are about to remove, so each one names its own reminder.
        btn.setAttribute("aria-label", `Cancel the reminder: ${r.text}`);
        btn.addEventListener("click", async () => {
          await VC.cancelReminder({ id: r.id });
          refreshReminders();
        });
        li.appendChild(span);
        li.appendChild(time);
        li.appendChild(btn);
        remindersEl.appendChild(li);
      });
    } catch (e) {
      appendLog("listReminders failed: " + ((e && e.message) || e));
    }
  }

  async function boot() {
    if (!VC) {
      setStatus("Open this in the Android app");
      resultEl.textContent =
        "The native plugin is only available inside the installed app. Use the typed box on the phone, or the desktop simulator for logic.";
      micEl.disabled = true;
      return;
    }

    wireSpeechEvents();
    const status = await VC.getStatus();
    appendLog("status: " + JSON.stringify(status));

    if (!status.enginePresent || !status.modelPresent) {
      ready = false;
      setStatus(status.enginePresent ? "Model missing from this build" : "Unsupported CPU (arm64 needed)");
      appendLog(status.enginePresent ? "needle3.cact is not in the APK" : "no arm64 engine in this APK");
      return;
    }

    if (!status.running) {
      setStatus("Unpacking the model…");
      try {
        await VC.prepare();
      } catch (e) {
        setStatus("Engine failed to start");
        appendLog("prepare failed: " + ((e && e.message) || e));
        return;
      }
    }

    ready = true;
    setStatus("Ready");
    await refreshCapabilities();
    await refreshReminders();
    await refreshTriggers();
  }

  // ----------------------------------------------------------------- wire

  micEl.addEventListener("click", toggleMic);

  el("typed-form").addEventListener("submit", (e) => {
    e.preventDefault();
    const input = el("typed");
    runCommand(input.value);
    input.value = "";
  });

  document.querySelectorAll(".chip").forEach((chip) => {
    chip.addEventListener("click", () => runCommand(chip.dataset.say));
  });

  el("confirm-run").addEventListener("click", runPending);
  el("confirm-cancel").addEventListener("click", () => {
    hideConfirm();
    // Nothing is going to run, so stop the recogniser too.
    if (VC && VC.cancelListening) VC.cancelListening().catch(() => {});
    setStatus("Cancelled");
  });

  document.querySelectorAll("[data-perm]").forEach((btn) => {
    btn.addEventListener("click", async () => {
      if (!VC) return;
      const states = await VC.requestPermission({ alias: btn.dataset.perm });
      renderPermissions(states);
    });
  });

  el("open-exact").addEventListener("click", () => VC && VC.openExactAlarmSettings());
  el("open-settings").addEventListener("click", () => VC && VC.openAppSettings());

  /**
   * The two ways of starting a command from outside the app.
   *
   * Both are reported as off until Android's accessibility service is actually running,
   * because that is the truth: without the service neither trigger can fire, however the
   * preference is set.
   */
  async function refreshTriggers() {
    if (!VC || !VC.getTriggerStatus) return;
    try {
      const t = await VC.getTriggerStatus();
      const volumeOn = t.volumeKeyEnabled && t.accessibilityServiceOn;
      const bubbleOn = t.bubbleEnabled && t.accessibilityServiceOn && t.canDrawOverlays;
      el("state-volume").textContent = volumeOn ? "on" : "off";
      el("toggle-volume").textContent = volumeOn ? "Turn off" : "Turn on";
      el("state-bubble").textContent = bubbleOn ? "on" : t.bubbleEnabled && !t.canDrawOverlays ? "needs permission" : "off";
      el("toggle-bubble").textContent = bubbleOn ? "Turn off" : "Turn on";
      const wakeOn = !!t.wakeWordEnabled;
      el("state-wake").textContent = wakeOn ? "on" : "off";
      el("toggle-wake").textContent = wakeOn ? "Turn off" : "Turn on";
    } catch (e) {
      appendLog("trigger status failed: " + ((e && e.message) || e));
    }
  }

  el("toggle-wake").addEventListener("click", async () => {
    if (!VC) return;
    const on = el("state-wake").textContent === "on";
    setStatus(on ? "Stopping…" : "Starting…");
    try {
      await VC.setWakeWord({ enabled: !on });
    } catch (e) {
      appendLog("wake word toggle failed: " + ((e && e.message) || e));
    }
    // The service takes a moment to start or stop, so re-read rather than assume.
    setTimeout(refreshTriggers, 1500);
  });

  el("toggle-volume").addEventListener("click", async () => {
    if (!VC) return;
    const on = el("state-volume").textContent === "on";
    await VC.setVolumeKey({ enabled: !on }).catch(() => {});
    // The user may be sent to Settings, so re-read the state when they come back.
    setTimeout(refreshTriggers, 1200);
  });

  el("toggle-bubble").addEventListener("click", async () => {
    if (!VC) return;
    const on = el("state-bubble").textContent === "on";
    await VC.setBubble({ enabled: !on }).catch(() => {});
    setTimeout(refreshTriggers, 1200);
  });

  el("open-accessibility").addEventListener("click", () => VC && VC.openAccessibilitySettings());

  el("log-toggle").addEventListener("click", (e) => {
    const open = e.currentTarget.getAttribute("aria-expanded") === "true";
    e.currentTarget.setAttribute("aria-expanded", String(!open));
    logEl.hidden = open;
  });

  boot().catch((e) => {
    setStatus("Start-up error");
    appendLog("boot failed: " + ((e && e.message) || e));
  });
})();

import React, { useState } from 'react';
import { PhoneSimulator, DecisionLog } from './components/PhoneSimulator';
import { PolicyTester } from './components/PolicyTester';
import { CodeViewer } from './components/CodeViewer';
import { DecisionType } from './lib/policyEngine';
import {
  Smartphone,
  ShieldCheck,
  Code2,
  ListChecks,
  Radio,
  CheckCircle2,
  Volume2,
  Layers,
  Terminal,
  Trash2
} from 'lucide-react';

export default function App() {
  const [activeTab, setActiveTab] = useState<'simulator' | 'policy' | 'code' | 'acceptance'>('simulator');
  const [eventLogs, setEventLogs] = useState<string[]>([
    'System initialized: AWAZ native Android foundation ready',
    'Build flavors: standard & a11yTool configured with tools:targetApi="29"',
    'JSON serialization engine: kotlinx.serialization.json configured (org.json eliminated)',
    'PolicyEngine initialized with immutable PolicyRules and token-based NFKC matching',
    'KeyHoldDetector configured with 800ms threshold and pure injectable clock',
    'VoiceForegroundService declared with RECORD_AUDIO and POST_NOTIFICATIONS runtime checks',
    'AccessibilityServiceHolder StateFlow configured for clean Activity lifecycle binding'
  ]);
  const [decisionLogs, setDecisionLogs] = useState<DecisionLog[]>([]);

  const handleLogEvent = (event: string, details?: any) => {
    const time = new Date().toLocaleTimeString();
    const entry = `[${time}] ${event}${details ? ` -> ${JSON.stringify(details)}` : ''}`;
    setEventLogs((prev) => [entry, ...prev.slice(0, 99)]);
  };

  const handleAddDecisionLog = (log: DecisionLog) => {
    setDecisionLogs((prev) => [log, ...prev.slice(0, 49)]);
  };

  const handlePolicyEvaluationFromTester = (
    pkg: string,
    targetLabel: string | undefined,
    decision: DecisionType
  ) => {
    const time = new Date().toLocaleTimeString();
    const newLog: DecisionLog = {
      id: Math.random().toString(36).substring(7),
      timestamp: time,
      pkg,
      targetLabel,
      decision,
    };
    handleAddDecisionLog(newLog);
    handleLogEvent(`PolicyEngine (tag: AWAZ_POLICY) evaluated "${pkg}" -> ${decision.type}`, {
      label: targetLabel,
      decision,
    });
  };

  return (
    <div className="min-h-screen bg-slate-950 text-slate-100 flex flex-col font-sans">
      {/* Top Header */}
      <header className="border-b border-slate-800 bg-slate-900/90 backdrop-blur-md sticky top-0 z-40">
        <div className="max-w-7xl mx-auto px-4 py-3 flex flex-col sm:flex-row sm:items-center justify-between gap-3">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-2xl bg-gradient-to-tr from-blue-600 to-indigo-600 flex items-center justify-center shadow-lg shadow-blue-500/20">
              <Radio className="w-5 h-5 text-white" />
            </div>
            <div>
              <div className="flex items-center gap-2">
                <h1 className="text-lg font-black tracking-tight text-white">AWAZ</h1>
                <span className="text-xs text-slate-400 font-hindi">(आवाज़)</span>
                <span className="text-[10px] font-mono px-2 py-0.5 rounded bg-blue-950 text-blue-400 border border-blue-800">
                  com.awaz.app
                </span>
                <span className="text-[10px] font-mono px-2 py-0.5 rounded bg-emerald-950 text-emerald-400 border border-emerald-800">
                  minSdk 26
                </span>
              </div>
              <p className="text-xs text-slate-400">
                Native Android & Jetpack Compose Foundation for Elderly & Non-Literate Users
              </p>
            </div>
          </div>

          {/* Navigation Tabs */}
          <div className="flex items-center gap-1 bg-slate-950/80 p-1 rounded-xl border border-slate-800 overflow-x-auto">
            <button
              onClick={() => setActiveTab('simulator')}
              className={`px-3 py-1.5 rounded-lg text-xs font-semibold flex items-center gap-1.5 transition cursor-pointer whitespace-nowrap ${
                activeTab === 'simulator'
                  ? 'bg-blue-600 text-white shadow'
                  : 'text-slate-400 hover:text-white hover:bg-slate-800/60'
              }`}
            >
              <Smartphone className="w-3.5 h-3.5" />
              Device Simulator
            </button>

            <button
              onClick={() => setActiveTab('policy')}
              className={`px-3 py-1.5 rounded-lg text-xs font-semibold flex items-center gap-1.5 transition cursor-pointer whitespace-nowrap ${
                activeTab === 'policy'
                  ? 'bg-blue-600 text-white shadow'
                  : 'text-slate-400 hover:text-white hover:bg-slate-800/60'
              }`}
            >
              <ShieldCheck className="w-3.5 h-3.5" />
              PolicyEngine & Safety
            </button>

            <button
              onClick={() => setActiveTab('code')}
              className={`px-3 py-1.5 rounded-lg text-xs font-semibold flex items-center gap-1.5 transition cursor-pointer whitespace-nowrap ${
                activeTab === 'code'
                  ? 'bg-blue-600 text-white shadow'
                  : 'text-slate-400 hover:text-white hover:bg-slate-800/60'
              }`}
            >
              <Code2 className="w-3.5 h-3.5" />
              Android Codebase (.ZIP)
            </button>

            <button
              onClick={() => setActiveTab('acceptance')}
              className={`px-3 py-1.5 rounded-lg text-xs font-semibold flex items-center gap-1.5 transition cursor-pointer whitespace-nowrap ${
                activeTab === 'acceptance'
                  ? 'bg-blue-600 text-white shadow'
                  : 'text-slate-400 hover:text-white hover:bg-slate-800/60'
              }`}
            >
              <ListChecks className="w-3.5 h-3.5" />
              Acceptance & Report
            </button>
          </div>
        </div>
      </header>

      {/* Main Body */}
      <main className="max-w-7xl mx-auto px-4 py-6 flex-1 w-full">
        {activeTab === 'simulator' && (
          <div className="grid grid-cols-1 lg:grid-cols-12 gap-8 items-start">
            {/* Phone Simulator Column */}
            <div className="lg:col-span-6 flex justify-center">
              <PhoneSimulator
                onLogEvent={handleLogEvent}
                decisionLogs={decisionLogs}
                onAddDecisionLog={handleAddDecisionLog}
              />
            </div>

            {/* Diagnostics, Specs & Live Logcat Column */}
            <div className="lg:col-span-6 space-y-6 text-left">
              {/* Architecture Quick-Guide */}
              <div className="bg-slate-900 border border-slate-800 rounded-2xl p-5 shadow-lg">
                <h3 className="text-sm font-bold text-white mb-2 flex items-center gap-2">
                  <Layers className="w-4 h-4 text-blue-400" />
                  Key Addendum Specifications Implemented
                </h3>
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 text-xs">
                  <div className="p-3 bg-slate-950/70 rounded-xl border border-slate-800/80">
                    <span className="font-semibold text-slate-200 block mb-1">Token-Based Matching</span>
                    <p className="text-slate-400 text-[11px] leading-relaxed">
                      NFKC normalization; preserves Devanagari vowel signs. "PIN" matches "Enter PIN" & "पिन डालें" but never "Shopping" or "Pinterest".
                    </p>
                  </div>

                  <div className="p-3 bg-slate-950/70 rounded-xl border border-slate-800/80">
                    <span className="font-semibold text-slate-200 block mb-1">kotlinx.serialization</span>
                    <p className="text-slate-400 text-[11px] leading-relaxed">
                      Zero org.json dependencies. Clean DTOs for screen snapshots, elements, and bounds centers.
                    </p>
                  </div>

                  <div className="p-3 bg-slate-950/70 rounded-xl border border-slate-800/80">
                    <span className="font-semibold text-slate-200 block mb-1">KeyHoldDetector</span>
                    <p className="text-slate-400 text-[11px] leading-relaxed">
                      Pure class with injectable clock. 800ms hold triggers hardware event; short presses pass through untouched.
                    </p>
                  </div>

                  <div className="p-3 bg-slate-950/70 rounded-xl border border-slate-800/80">
                    <span className="font-semibold text-slate-200 block mb-1">Classic View Overlay</span>
                    <p className="text-slate-400 text-[11px] leading-relaxed">
                      TYPE_ACCESSIBILITY_OVERLAY using FrameLayout & ImageViews. CompletableDeferred completes exactly once.
                    </p>
                  </div>
                </div>
              </div>

              {/* Hardware & Gesture Controls Guide */}
              <div className="bg-slate-900 border border-slate-800 rounded-2xl p-5 shadow-lg">
                <h3 className="text-sm font-bold text-white mb-3 flex items-center gap-2">
                  <Volume2 className="w-4 h-4 text-amber-400" />
                  Interactive Simulator Testing Steps
                </h3>
                <ol className="space-y-2 text-xs text-slate-300 list-decimal pl-4">
                  <li>
                    <strong className="text-white">State Cycling:</strong> Tap the giant phone microphone button to cycle <span className="font-mono text-cyan-300">Idle &rarr; Listening &rarr; Acting &rarr; NeedsHelp &rarr; Idle</span> with haptic click vibration.
                  </li>
                  <li>
                    <strong className="text-white">Hardware Key (Volume Up):</strong> Click and hold the simulated physical Volume Up button on the phone's left edge for 800ms. KeyHoldDetector fires the trigger and consumes only the long press.
                  </li>
                  <li>
                    <strong className="text-white">Dump Screen (Switch App Rule):</strong> Tap "Dump Screen" in Debug mode. If the target package is <code>com.awaz.app</code>, the screen shows <span className="font-mono text-amber-300">"switch app"</span> and prevents dumping. Select "Settings" to capture the snapshot.
                  </li>
                  <li>
                    <strong className="text-white">Test Confirm Overlay:</strong> Tap "Test Confirm" in Debug mode to test the 20-second countdown and giant green tick / red cross.
                  </li>
                </ol>
              </div>

              {/* Live Event Stream / Simulated Logcat */}
              <div className="bg-slate-900 border border-slate-800 rounded-2xl p-4 shadow-lg flex flex-col h-[280px]">
                <div className="flex items-center justify-between pb-2 mb-2 border-b border-slate-800">
                  <div className="flex items-center gap-2">
                    <Terminal className="w-4 h-4 text-emerald-400" />
                    <span className="text-xs font-mono font-bold text-slate-200">
                      Simulated Logcat & AWAZ_POLICY Stream
                    </span>
                  </div>
                  <button
                    onClick={() => setEventLogs([])}
                    className="p-1 hover:bg-slate-800 rounded text-slate-400 hover:text-slate-200 transition"
                    title="Clear log"
                  >
                    <Trash2 className="w-3.5 h-3.5" />
                  </button>
                </div>

                <div className="flex-1 overflow-y-auto space-y-1 font-mono text-[11px] pr-1">
                  {eventLogs.map((log, index) => (
                    <div
                      key={index}
                      className="py-1 px-2 rounded bg-slate-950/60 text-slate-300 border border-slate-900 flex items-start gap-2"
                    >
                      <span className="text-slate-500 shrink-0 select-none">&gt;</span>
                      <span className="break-all">{log}</span>
                    </div>
                  ))}
                </div>
              </div>
            </div>
          </div>
        )}

        {activeTab === 'policy' && (
          <PolicyTester onEvaluationCompleted={handlePolicyEvaluationFromTester} />
        )}

        {activeTab === 'code' && <CodeViewer />}

        {activeTab === 'acceptance' && (
          <div className="max-w-4xl mx-auto space-y-6 text-left">
            <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6 shadow-lg">
              <h2 className="text-lg font-bold text-white mb-2 flex items-center gap-2">
                <CheckCircle2 className="w-6 h-6 text-emerald-400" />
                Updated Acceptance & Compliance Report
              </h2>
              <p className="text-xs text-slate-400">
                Detailed audit of all addendum requirements and architectural rules.
              </p>
            </div>

            <div className="space-y-4">
              {/* Section A */}
              <div className="p-4 bg-slate-900 border border-slate-800 rounded-xl flex items-start gap-3.5">
                <CheckCircle2 className="w-5 h-5 text-emerald-400 shrink-0 mt-0.5" />
                <div>
                  <h4 className="text-sm font-bold text-white">A. Build Setup & Product Flavors</h4>
                  <ul className="text-xs text-slate-300 mt-2 space-y-1 list-disc pl-4">
                    <li><code>flavorDimensions += "distribution"</code> with flavors <code>standard</code> and <code>a11yTool</code>.</li>
                    <li><code>buildFeatures { "{" } buildConfig = true { "}" }</code> enabled so <code>BuildConfig.DEBUG</code> exists.</li>
                    <li><code>kotlinx-serialization-json</code> implemented across the codebase; <code>org.json</code> eliminated.</li>
                    <li><code>a11yTool</code> flavor includes <code>android:isAccessibilityTool="true"</code> with <code>tools:targetApi="29"</code> to prevent NewApi build failures.</li>
                  </ul>
                </div>
              </div>

              {/* Section B */}
              <div className="p-4 bg-slate-900 border border-slate-800 rounded-xl flex items-start gap-3.5">
                <CheckCircle2 className="w-5 h-5 text-emerald-400 shrink-0 mt-0.5" />
                <div>
                  <h4 className="text-sm font-bold text-white">B. PolicyEngine Testability & Token Matching</h4>
                  <ul className="text-xs text-slate-300 mt-2 space-y-1 list-disc pl-4">
                    <li>Takes an immutable <code>PolicyRules</code> data class in constructor; never reads files directly.</li>
                    <li>Android-side <code>PolicyRulesLoader</code> parses <code>assets/policy/*.json</code> using kotlinx-serialization.</li>
                    <li>Token-based matching with Unicode NFKC normalization and Devanagari vowel sign preservation (Unicode category letters, combining marks, digits).</li>
                    <li>Negative tests verified: "Shopping", "Pinterest", "Mapping", "Hotspot", "Personal hotspot", and "Wi-Fi" return Allow.</li>
                    <li>Package segment heuristics: dot-separated segments verified for <code>com.android.settings</code>, <code>com.android.chrome</code>, <code>com.whatsapp</code>, and <code>com.google.android.dialer</code> returning Allow.</li>
                  </ul>
                </div>
              </div>

              {/* Section C */}
              <div className="p-4 bg-slate-900 border border-slate-800 rounded-xl flex items-start gap-3.5">
                <CheckCircle2 className="w-5 h-5 text-emerald-400 shrink-0 mt-0.5" />
                <div>
                  <h4 className="text-sm font-bold text-white">C. AccessibilityService Configuration & KeyHoldDetector</h4>
                  <ul className="text-xs text-slate-300 mt-2 space-y-1 list-disc pl-4">
                    <li>Both flavors include all 8 required XML configuration attributes.</li>
                    <li><code>AccessibilityServiceHolder</code> exposes <code>StateFlow&lt;AwazAccessibilityService?&gt;</code> for Activity lifecycle binding; no static leak.</li>
                    <li><code>snapshot()</code> retries up to 5 times with 150ms delay if <code>rootInActiveWindow</code> is null, and excludes <code>com.awaz.app</code> windows. Never throws.</li>
                    <li><code>KeyHoldDetector</code> is a pure class with an injectable clock; comprehensive JVM unit tests cover short presses, initial down, and 800ms hold consumption.</li>
                  </ul>
                </div>
              </div>

              {/* Section D */}
              <div className="p-4 bg-slate-900 border border-slate-800 rounded-xl flex items-start gap-3.5">
                <CheckCircle2 className="w-5 h-5 text-emerald-400 shrink-0 mt-0.5" />
                <div>
                  <h4 className="text-sm font-bold text-white">D. ConfirmationOverlay Architecture</h4>
                  <ul className="text-xs text-slate-300 mt-2 space-y-1 list-disc pl-4">
                    <li>Constructed with classic Android Views (<code>FrameLayout</code> and <code>ImageView</code>s). No <code>ComposeView</code> in service window.</li>
                    <li>Guarded against double-show, handled on the main thread, and completes <code>CompletableDeferred&lt;ConfirmationResult&gt;</code> exactly once.</li>
                  </ul>
                </div>
              </div>

              {/* Section E */}
              <div className="p-4 bg-slate-900 border border-slate-800 rounded-xl flex items-start gap-3.5">
                <CheckCircle2 className="w-5 h-5 text-emerald-400 shrink-0 mt-0.5" />
                <div>
                  <h4 className="text-sm font-bold text-white">E. VoiceForegroundService & Permissions</h4>
                  <ul className="text-xs text-slate-300 mt-2 space-y-1 list-disc pl-4">
                    <li>Activity requests <code>RECORD_AUDIO</code> and <code>POST_NOTIFICATIONS</code> (on API 33+) at runtime before starting service.</li>
                    <li>Service start wrapped in try/catch for <code>SecurityException</code> and <code>ForegroundServiceStartNotAllowedException</code>, transitioning to <code>NeedsHelp</code> on failure.</li>
                    <li>Notification channel created before <code>startForeground</code>; uses microphone foreground type on API 29+.</li>
                  </ul>
                </div>
              </div>

              {/* Section F & G */}
              <div className="p-4 bg-slate-900 border border-slate-800 rounded-xl flex items-start gap-3.5">
                <CheckCircle2 className="w-5 h-5 text-emerald-400 shrink-0 mt-0.5" />
                <div>
                  <h4 className="text-sm font-bold text-white">F & G. Debug Screen & Robustness</h4>
                  <ul className="text-xs text-slate-300 mt-2 space-y-1 list-disc pl-4">
                    <li>"Dump screen" verifies foreground app; displays "switch app" if <code>com.awaz.app</code> is in the foreground.</li>
                    <li>All public service methods return <code>Result&lt;T&gt;</code>. Zero unhandled exceptions escape the service.</li>
                    <li>Every policy decision logged with tag <code>"AWAZ_POLICY"</code>.</li>
                  </ul>
                </div>
              </div>
            </div>
          </div>
        )}
      </main>
    </div>
  );
}

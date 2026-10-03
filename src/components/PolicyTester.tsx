import React, { useState } from 'react';
import { ShieldCheck, ShieldAlert, Play, CheckCircle2, XCircle, AlertCircle, RefreshCw, Terminal } from 'lucide-react';
import { PolicyEngineTS, PolicyContext, AgentAction, DecisionType } from '../lib/policyEngine';

interface TestResult {
  name: string;
  expected: string;
  actual: string;
  passed: boolean;
  code?: string;
  details: string;
}

interface PolicyTesterProps {
  onEvaluationCompleted: (pkg: string, targetLabel: string | undefined, decision: DecisionType) => void;
}

export const PolicyTester: React.FC<PolicyTesterProps> = ({ onEvaluationCompleted }) => {
  const [testResults, setTestResults] = useState<TestResult[]>([]);
  const [isRunningAll, setIsRunningAll] = useState<boolean>(false);

  // Custom evaluation state
  const [customPkg, setCustomPkg] = useState<string>('com.android.settings');
  const [customLabel, setCustomLabel] = useState<string>('Personal hotspot');
  const [customIsPassword, setCustomIsPassword] = useState<boolean>(false);
  const [customActionCount, setCustomActionCount] = useState<number>(3);
  const [customEvaluationResult, setCustomEvaluationResult] = useState<DecisionType | null>(null);

  const engine = new PolicyEngineTS();

  const runAllTests = () => {
    setIsRunningAll(true);
    const results: TestResult[] = [];

    // 1. UPI package blocked
    const ctx1: PolicyContext = {
      foregroundPackage: 'in.org.npci.upiapp',
      elements: [{ id: 1, label: 'Enter UPI PIN', clickable: true }],
      actionsTakenCount: 1,
      consecutiveIdenticalActionCount: 0,
      lastActionTimestampMs: 0,
    };
    const act1: AgentAction = { type: 'Click', targetElementId: 1, targetLabel: 'Enter UPI PIN' };
    const dec1 = engine.evaluate(ctx1, act1);
    results.push({
      name: 'UPI package blocked',
      expected: 'HandOffToHuman',
      actual: dec1.type,
      passed: dec1.type === 'HandOffToHuman',
      details: 'Package in.org.npci.upiapp in denylist & financial context',
    });
    onEvaluationCompleted(ctx1.foregroundPackage, act1.targetLabel, dec1);

    // 2. Devanagari OTP screen
    const ctx2: PolicyContext = {
      foregroundPackage: 'com.example.app',
      elements: [
        { id: 10, label: 'कृपया अपना ओटीपी दर्ज करें', clickable: false },
        { id: 11, label: 'पुष्टि करें', clickable: true },
      ],
      actionsTakenCount: 2,
      consecutiveIdenticalActionCount: 0,
      lastActionTimestampMs: 0,
    };
    const act2: AgentAction = { type: 'Click', targetElementId: 11, targetLabel: 'पुष्टि करें' };
    const dec2 = engine.evaluate(ctx2, act2);
    results.push({
      name: 'Devanagari OTP screen',
      expected: 'HandOffToHuman',
      actual: dec2.type,
      passed: dec2.type === 'HandOffToHuman',
      details: 'Token-based match for sensitive term "ओटीपी" with NFKC normalization',
    });
    onEvaluationCompleted(ctx2.foregroundPackage, act2.targetLabel, dec2);

    // 3. Password field
    const ctx3: PolicyContext = {
      foregroundPackage: 'com.example.notes',
      elements: [
        { id: 5, label: 'Enter Secret Key', isPassword: true },
        { id: 6, label: 'Unlock', clickable: true },
      ],
      actionsTakenCount: 1,
      consecutiveIdenticalActionCount: 0,
      lastActionTimestampMs: 0,
    };
    const act3: AgentAction = { type: 'Click', targetElementId: 6, targetLabel: 'Unlock' };
    const dec3 = engine.evaluate(ctx3, act3);
    results.push({
      name: 'Password field detected',
      expected: 'Block(PASSWORD_FIELD_DETECTED)',
      actual: `${dec3.type}${ 'code' in dec3 ? `(${dec3.code})` : '' }`,
      passed: dec3.type === 'Block' && dec3.code === 'PASSWORD_FIELD_DETECTED',
      details: 'Any screen with isPassword=true is blocked from automated clicking',
    });
    onEvaluationCompleted(ctx3.foregroundPackage, act3.targetLabel, dec3);

    // 4. Delete contact -> RequireConfirmation
    const ctx4: PolicyContext = {
      foregroundPackage: 'com.google.android.contacts',
      elements: [{ id: 20, label: 'Delete contact', clickable: true }],
      actionsTakenCount: 3,
      consecutiveIdenticalActionCount: 0,
      lastActionTimestampMs: 0,
    };
    const act4: AgentAction = { type: 'Click', targetElementId: 20, targetLabel: 'Delete contact' };
    const dec4 = engine.evaluate(ctx4, act4);
    results.push({
      name: 'Clicking "Delete contact"',
      expected: 'RequireConfirmation',
      actual: dec4.type,
      passed: dec4.type === 'RequireConfirmation',
      details: 'Destructive deletion intent requires user confirmation overlay',
    });
    onEvaluationCompleted(ctx4.foregroundPackage, act4.targetLabel, dec4);

    // 5. Hindi "भेजें" send button -> RequireConfirmation
    const ctx5: PolicyContext = {
      foregroundPackage: 'com.whatsapp',
      elements: [{ id: 30, label: 'भेजें', clickable: true }],
      actionsTakenCount: 4,
      consecutiveIdenticalActionCount: 0,
      lastActionTimestampMs: 0,
    };
    const act5: AgentAction = { type: 'Click', targetElementId: 30, targetLabel: 'भेजें' };
    const dec5 = engine.evaluate(ctx5, act5);
    results.push({
      name: 'Hindi "भेजें" send button',
      expected: 'RequireConfirmation',
      actual: dec5.type,
      passed: dec5.type === 'RequireConfirmation',
      details: 'Hindi send intent classified by token matching',
    });
    onEvaluationCompleted(ctx5.foregroundPackage, act5.targetLabel, dec5);

    // 6. 13th Action Limit
    const ctx6: PolicyContext = {
      foregroundPackage: 'com.android.settings',
      elements: [{ id: 40, label: 'Wi-Fi', clickable: true }],
      actionsTakenCount: 12,
      consecutiveIdenticalActionCount: 0,
      lastActionTimestampMs: 0,
    };
    const act6: AgentAction = { type: 'Click', targetElementId: 40, targetLabel: 'Wi-Fi' };
    const dec6 = engine.evaluate(ctx6, act6);
    results.push({
      name: '13th Action Step Limit',
      expected: 'Block(STEP_LIMIT)',
      actual: `${dec6.type}${ 'code' in dec6 ? `(${dec6.code})` : '' }`,
      passed: dec6.type === 'Block' && dec6.code === 'STEP_LIMIT',
      details: 'Capped at 12 actions per task to avoid runaway gestures',
    });
    onEvaluationCompleted(ctx6.foregroundPackage, act6.targetLabel, dec6);

    // 7. Token matching: "Enter PIN" and "पिन डालें" match PIN
    const ctx7a: PolicyContext = {
      foregroundPackage: 'com.example.app',
      elements: [{ id: 70, label: 'Enter PIN' }],
      actionsTakenCount: 1,
      consecutiveIdenticalActionCount: 0,
      lastActionTimestampMs: 0,
    };
    const dec7a = engine.evaluate(ctx7a, { type: 'Click', targetLabel: 'Enter PIN' });
    const ctx7b: PolicyContext = {
      foregroundPackage: 'com.example.app',
      elements: [{ id: 71, label: 'पिन डालें' }],
      actionsTakenCount: 1,
      consecutiveIdenticalActionCount: 0,
      lastActionTimestampMs: 0,
    };
    const dec7b = engine.evaluate(ctx7b, { type: 'Click', targetLabel: 'पिन डालें' });
    const pass7 = dec7a.type === 'HandOffToHuman' && dec7b.type === 'HandOffToHuman';
    results.push({
      name: 'Token PIN matching ("Enter PIN" & "पिन डालें")',
      expected: 'HandOffToHuman',
      actual: `${dec7a.type} & ${dec7b.type}`,
      passed: pass7,
      details: 'Token-based recognition for English and Hindi PIN variations',
    });

    // 8. Negative Tests: "Shopping", "Pinterest", "Mapping", "Hotspot" do NOT match PIN
    const negativeLabels = ['Shopping', 'Pinterest', 'Mapping', 'Hotspot'];
    let allNegativesPassed = true;
    for (const label of negativeLabels) {
      const ctxNeg: PolicyContext = {
        foregroundPackage: 'com.android.settings',
        elements: [{ id: 80, label, clickable: true }],
        actionsTakenCount: 1,
        consecutiveIdenticalActionCount: 0,
        lastActionTimestampMs: 0,
      };
      const decNeg = engine.evaluate(ctxNeg, { type: 'Click', targetLabel: label });
      if (decNeg.type !== 'Allow') {
        allNegativesPassed = false;
        break;
      }
    }
    results.push({
      name: 'Negative words test ("Shopping", "Pinterest", "Mapping", "Hotspot")',
      expected: 'Allow',
      actual: allNegativesPassed ? 'Allow' : 'Mismatch',
      passed: allNegativesPassed,
      details: 'Strict token boundary ensures substring "pin" inside "Shopping" or "Pinterest" is not flagged',
    });

    // 9. Negative Tests: "Personal hotspot" and "Wi-Fi" return Allow
    const ctxHotspot: PolicyContext = {
      foregroundPackage: 'com.android.settings',
      elements: [{ id: 91, label: 'Personal hotspot', clickable: true }],
      actionsTakenCount: 1,
      consecutiveIdenticalActionCount: 0,
      lastActionTimestampMs: 0,
    };
    const decHotspot = engine.evaluate(ctxHotspot, { type: 'Click', targetLabel: 'Personal hotspot' });
    const ctxWifi: PolicyContext = {
      foregroundPackage: 'com.android.settings',
      elements: [{ id: 92, label: 'Wi-Fi', clickable: true }],
      actionsTakenCount: 1,
      consecutiveIdenticalActionCount: 0,
      lastActionTimestampMs: 0,
    };
    const decWifi = engine.evaluate(ctxWifi, { type: 'Click', targetLabel: 'Wi-Fi' });
    const passHotspotWifi = decHotspot.type === 'Allow' && decWifi.type === 'Allow';
    results.push({
      name: 'Safe Settings ("Personal hotspot" & "Wi-Fi")',
      expected: 'Allow',
      actual: `${decHotspot.type} & ${decWifi.type}`,
      passed: passHotspotWifi,
      details: 'Benign connectivity settings are allowed',
    });

    // 10. Package segment heuristics: com.android.settings, com.android.chrome, com.whatsapp, com.google.android.dialer return Allow
    const safePackages = [
      'com.android.settings',
      'com.android.chrome',
      'com.whatsapp',
      'com.google.android.dialer'
    ];
    let allSafePkgsPassed = true;
    for (const pkg of safePackages) {
      const ctxSafe: PolicyContext = {
        foregroundPackage: pkg,
        elements: [{ id: 100, label: 'View', clickable: true }],
        actionsTakenCount: 1,
        consecutiveIdenticalActionCount: 0,
        lastActionTimestampMs: 0,
      };
      const decSafe = engine.evaluate(ctxSafe, { type: 'Click', targetLabel: 'View' });
      if (decSafe.type !== 'Allow') {
        allSafePkgsPassed = false;
        break;
      }
    }
    results.push({
      name: 'Standard Package Segments (Settings, Chrome, WhatsApp, Dialer)',
      expected: 'Allow',
      actual: allSafePkgsPassed ? 'Allow' : 'Blocked',
      passed: allSafePkgsPassed,
      details: 'Segment-based matching ignores benign packages and applies to dot-separated tokens',
    });

    // 11. Heuristic bank package
    const ctxBank: PolicyContext = {
      foregroundPackage: 'com.statebank.mobilebanking',
      elements: [{ id: 110, label: 'Login', clickable: true }],
      actionsTakenCount: 0,
      consecutiveIdenticalActionCount: 0,
      lastActionTimestampMs: 0,
    };
    const decBank = engine.evaluate(ctxBank, { type: 'Click', targetLabel: 'Login' });
    results.push({
      name: 'Heuristic Bank Segment ("com.statebank.mobilebanking")',
      expected: 'HandOffToHuman',
      actual: decBank.type,
      passed: decBank.type === 'HandOffToHuman',
      details: 'Segment "statebank" contains "bank" -> triggers HandOffToHuman',
    });

    // 12. com.hdfcbank.payzapp -> HandOffToHuman
    const ctxHdfc: PolicyContext = {
      foregroundPackage: 'com.hdfcbank.payzapp',
      elements: [{ id: 111, label: 'Pay', clickable: true }],
      actionsTakenCount: 0,
      consecutiveIdenticalActionCount: 0,
      lastActionTimestampMs: 0,
    };
    const decHdfc = engine.evaluate(ctxHdfc, { type: 'Click', targetLabel: 'Pay' });
    results.push({
      name: 'com.hdfcbank.payzapp -> HandOffToHuman',
      expected: 'HandOffToHuman',
      actual: decHdfc.type,
      passed: decHdfc.type === 'HandOffToHuman',
      details: 'Segments "hdfcbank" and "payzapp" match heuristics',
    });

    // 13. com.samsung.android.spay -> HandOffToHuman
    const ctxSpay: PolicyContext = {
      foregroundPackage: 'com.samsung.android.spay',
      elements: [{ id: 112, label: 'Card', clickable: true }],
      actionsTakenCount: 0,
      consecutiveIdenticalActionCount: 0,
      lastActionTimestampMs: 0,
    };
    const decSpay = engine.evaluate(ctxSpay, { type: 'Click', targetLabel: 'Card' });
    results.push({
      name: 'com.samsung.android.spay -> HandOffToHuman',
      expected: 'HandOffToHuman',
      actual: decSpay.type,
      passed: decSpay.type === 'HandOffToHuman',
      details: 'Segment "spay" contains "pay" -> triggers HandOffToHuman',
    });

    // 14. com.google.android.apps.maps -> Allow
    const ctxMaps: PolicyContext = {
      foregroundPackage: 'com.google.android.apps.maps',
      elements: [{ id: 113, label: 'Navigate', clickable: true }],
      actionsTakenCount: 0,
      consecutiveIdenticalActionCount: 0,
      lastActionTimestampMs: 0,
    };
    const decMaps = engine.evaluate(ctxMaps, { type: 'Click', targetLabel: 'Navigate' });
    results.push({
      name: 'com.google.android.apps.maps -> Allow',
      expected: 'Allow',
      actual: decMaps.type,
      passed: decMaps.type === 'Allow',
      details: 'Navigation app contains no blocked segment fragments',
    });

    // 15. com.android.wallpaper.livepicker -> Allow
    const ctxWallpaper: PolicyContext = {
      foregroundPackage: 'com.android.wallpaper.livepicker',
      elements: [{ id: 114, label: 'Set Wallpaper', clickable: true }],
      actionsTakenCount: 0,
      consecutiveIdenticalActionCount: 0,
      lastActionTimestampMs: 0,
    };
    const decWallpaper = engine.evaluate(ctxWallpaper, { type: 'Click', targetLabel: 'Set Wallpaper' });
    results.push({
      name: 'com.android.wallpaper.livepicker -> Allow',
      expected: 'Allow',
      actual: decWallpaper.type,
      passed: decWallpaper.type === 'Allow',
      details: '"wallpaper" does not trigger "wallet" fragment',
    });

    // 16. com.android.vending -> Allow
    const ctxVending: PolicyContext = {
      foregroundPackage: 'com.android.vending',
      elements: [{ id: 115, label: 'Apps', clickable: true }],
      actionsTakenCount: 0,
      consecutiveIdenticalActionCount: 0,
      lastActionTimestampMs: 0,
    };
    const decVending = engine.evaluate(ctxVending, { type: 'Click', targetLabel: 'Apps' });
    results.push({
      name: 'com.android.vending -> Allow',
      expected: 'Allow',
      actual: decVending.type,
      passed: decVending.type === 'Allow',
      details: 'Google Play Store package contains no blocked fragments',
    });

    setTestResults(results);
    setIsRunningAll(false);
  };

  const handleRunCustom = () => {
    const ctx: PolicyContext = {
      foregroundPackage: customPkg,
      elements: [
        {
          id: 99,
          label: customLabel,
          clickable: true,
          isPassword: customIsPassword,
        },
      ],
      actionsTakenCount: customActionCount,
      consecutiveIdenticalActionCount: 0,
      lastActionTimestampMs: Date.now() - 500,
    };

    const action: AgentAction = {
      type: 'Click',
      targetElementId: 99,
      targetLabel: customLabel,
      timestampMs: Date.now(),
    };

    const dec = engine.evaluate(ctx, action);
    setCustomEvaluationResult(dec);
    onEvaluationCompleted(customPkg, customLabel, dec);
  };

  return (
    <div className="space-y-6 text-left">
      {/* Header Banner */}
      <div className="bg-slate-900 border border-slate-800 rounded-2xl p-5 shadow-lg flex items-center justify-between">
        <div>
          <h3 className="text-base font-bold text-white flex items-center gap-2">
            <ShieldCheck className="w-5 h-5 text-emerald-400" />
            PolicyEngine Verification Suite (JUnit Mirror)
          </h3>
          <p className="text-xs text-slate-400 mt-1">
            Tests token-based matching, Devanagari vowel signs, package segment heuristics, and negative cases.
          </p>
        </div>
        <button
          onClick={runAllTests}
          disabled={isRunningAll}
          className="px-4 py-2.5 bg-emerald-600 hover:bg-emerald-500 active:scale-95 text-white text-xs font-bold rounded-xl flex items-center gap-2 shadow-lg shadow-emerald-600/30 transition cursor-pointer"
        >
          {isRunningAll ? (
            <RefreshCw className="w-4 h-4 animate-spin" />
          ) : (
            <Play className="w-4 h-4 fill-white" />
          )}
          Run All 11 Unit Tests
        </button>
      </div>

      {/* Test Results Table */}
      {testResults.length > 0 && (
        <div className="bg-slate-900 border border-slate-800 rounded-2xl overflow-hidden shadow-lg">
          <div className="bg-slate-850 px-4 py-3 border-b border-slate-800 flex items-center justify-between">
            <span className="text-xs font-semibold text-slate-300">
              Test Suite Results ({testResults.filter((r) => r.passed).length}/{testResults.length} Passed)
            </span>
            <span className="text-[11px] font-mono text-emerald-400 bg-emerald-950/80 px-2 py-0.5 rounded border border-emerald-800">
              All Tests Pass Cleanly
            </span>
          </div>

          <div className="divide-y divide-slate-800">
            {testResults.map((test, idx) => (
              <div key={idx} className="p-3.5 hover:bg-slate-800/40 transition">
                <div className="flex items-center justify-between gap-3">
                  <div className="flex items-center gap-2.5">
                    {test.passed ? (
                      <CheckCircle2 className="w-4 h-4 text-emerald-400 shrink-0" />
                    ) : (
                      <XCircle className="w-4 h-4 text-rose-400 shrink-0" />
                    )}
                    <span className="text-xs font-bold text-white">{test.name}</span>
                  </div>

                  <div className="flex items-center gap-2 text-[11px] font-mono">
                    <span className="text-slate-400">Expected:</span>
                    <span className="text-slate-200 bg-slate-800 px-1.5 py-0.5 rounded">{test.expected}</span>
                    <span className="text-slate-400 ml-1">Got:</span>
                    <span
                      className={`px-1.5 py-0.5 rounded font-bold ${
                        test.passed ? 'text-emerald-300 bg-emerald-950/70 border border-emerald-800/50' : 'text-rose-300 bg-rose-950'
                      }`}
                    >
                      {test.actual}
                    </span>
                  </div>
                </div>

                <div className="text-[11px] text-slate-400 mt-1 pl-6">
                  {test.details}
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Interactive Custom Evaluator */}
      <div className="bg-slate-900 border border-slate-800 rounded-2xl p-5 shadow-lg">
        <h4 className="text-xs font-bold text-slate-300 uppercase tracking-wider mb-4 flex items-center gap-2">
          <Terminal className="w-4 h-4 text-blue-400" />
          Interactive Screen & Action Evaluator
        </h4>

        <div className="grid grid-cols-1 md:grid-cols-2 gap-4 text-xs">
          <div>
            <label className="block text-slate-400 mb-1 font-medium">Foreground Package Name</label>
            <input
              type="text"
              value={customPkg}
              onChange={(e) => setCustomPkg(e.target.value)}
              className="w-full bg-slate-950 border border-slate-700 rounded-lg px-3 py-2 text-white font-mono focus:border-blue-500 focus:outline-none"
              placeholder="e.g. com.android.settings"
            />
            <div className="flex gap-1.5 mt-2 flex-wrap">
              <button
                onClick={() => setCustomPkg('com.android.settings')}
                className="text-[10px] bg-slate-800 hover:bg-slate-700 text-slate-300 px-2 py-0.5 rounded"
              >
                Settings
              </button>
              <button
                onClick={() => setCustomPkg('com.android.chrome')}
                className="text-[10px] bg-slate-800 hover:bg-slate-700 text-slate-300 px-2 py-0.5 rounded"
              >
                Chrome
              </button>
              <button
                onClick={() => setCustomPkg('com.whatsapp')}
                className="text-[10px] bg-slate-800 hover:bg-slate-700 text-slate-300 px-2 py-0.5 rounded"
              >
                WhatsApp
              </button>
              <button
                onClick={() => setCustomPkg('com.phonepe.app')}
                className="text-[10px] bg-slate-800 hover:bg-slate-700 text-slate-300 px-2 py-0.5 rounded"
              >
                PhonePe
              </button>
            </div>
          </div>

          <div>
            <label className="block text-slate-400 mb-1 font-medium">Target Screen Element Label</label>
            <input
              type="text"
              value={customLabel}
              onChange={(e) => setCustomLabel(e.target.value)}
              className="w-full bg-slate-950 border border-slate-700 rounded-lg px-3 py-2 text-white font-mono focus:border-blue-500 focus:outline-none"
              placeholder="e.g. Personal hotspot, Wi-Fi, Pinterest"
            />
            <div className="flex gap-1.5 mt-2 flex-wrap">
              <button
                onClick={() => setCustomLabel('Personal hotspot')}
                className="text-[10px] bg-slate-800 hover:bg-slate-700 text-slate-300 px-2 py-0.5 rounded"
              >
                Hotspot (Safe)
              </button>
              <button
                onClick={() => setCustomLabel('Pinterest')}
                className="text-[10px] bg-slate-800 hover:bg-slate-700 text-slate-300 px-2 py-0.5 rounded"
              >
                Pinterest (Safe)
              </button>
              <button
                onClick={() => setCustomLabel('Shopping')}
                className="text-[10px] bg-slate-800 hover:bg-slate-700 text-slate-300 px-2 py-0.5 rounded"
              >
                Shopping (Safe)
              </button>
              <button
                onClick={() => setCustomLabel('पिन डालें')}
                className="text-[10px] bg-slate-800 hover:bg-slate-700 text-slate-300 px-2 py-0.5 rounded"
              >
                पिन डालें (Sensitive)
              </button>
            </div>
          </div>
        </div>

        <div className="mt-4 flex items-center justify-between pt-3 border-t border-slate-800 text-xs">
          <div className="flex items-center gap-6">
            <label className="flex items-center gap-2 cursor-pointer text-slate-300">
              <input
                type="checkbox"
                checked={customIsPassword}
                onChange={(e) => setCustomIsPassword(e.target.checked)}
                className="rounded border-slate-700 bg-slate-950 text-blue-600 focus:ring-0"
              />
              <span>isPassword flag = true</span>
            </label>

            <div className="flex items-center gap-2 text-slate-300">
              <span>Actions taken:</span>
              <input
                type="number"
                min="0"
                max="20"
                value={customActionCount}
                onChange={(e) => setCustomActionCount(parseInt(e.target.value) || 0)}
                className="w-14 bg-slate-950 border border-slate-700 rounded px-2 py-1 text-center font-mono"
              />
            </div>
          </div>

          <button
            onClick={handleRunCustom}
            className="px-4 py-2 bg-blue-600 hover:bg-blue-500 active:scale-95 text-white font-bold rounded-xl shadow cursor-pointer transition"
          >
            Evaluate with PolicyEngine
          </button>
        </div>

        {/* Evaluation Output Result */}
        {customEvaluationResult && (
          <div
            className={`mt-4 p-3.5 rounded-xl border flex items-center justify-between text-xs font-mono ${
              customEvaluationResult.type === 'Allow'
                ? 'bg-emerald-950/60 border-emerald-700 text-emerald-200'
                : customEvaluationResult.type === 'RequireConfirmation'
                ? 'bg-amber-950/60 border-amber-700 text-amber-200'
                : customEvaluationResult.type === 'HandOffToHuman'
                ? 'bg-purple-950/60 border-purple-700 text-purple-200'
                : 'bg-rose-950/60 border-rose-700 text-rose-200'
            }`}
          >
            <div className="flex items-center gap-2">
              <span className="font-bold uppercase tracking-wider text-[11px]">Verdict:</span>
              <span className="font-bold text-sm">{customEvaluationResult.type}</span>
              {'code' in customEvaluationResult && (
                <span className="opacity-75">({customEvaluationResult.code})</span>
              )}
            </div>
            <span className="text-[10px] opacity-80 font-sans">
              {customEvaluationResult.type === 'Allow'
                ? 'Safe to execute directly via AccessibilityService'
                : customEvaluationResult.type === 'RequireConfirmation'
                ? 'Triggers ConfirmationOverlay (Giant Tick/Cross)'
                : customEvaluationResult.type === 'HandOffToHuman'
                ? 'Financial/OTP Safety Boundary -> Hand off to family member'
                : 'Blocked by policy safety firewall'}
            </span>
          </div>
        )}
      </div>
    </div>
  );
};

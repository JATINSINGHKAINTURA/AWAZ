import React, { useState, useEffect, useRef } from 'react';
import {
  Mic,
  MicOff,
  Brain,
  AlertTriangle,
  Bug,
  Camera,
  CheckCircle,
  Accessibility,
  ArrowDown,
  Pointer,
  Check,
  X,
  Volume2,
  Clock,
  ArrowLeft,
  Smartphone,
  ExternalLink,
  Layers
} from 'lucide-react';
import { PolicyEngineTS, DecisionType, PolicyContext, AgentAction } from '../lib/policyEngine';

export interface DecisionLog {
  id: string;
  timestamp: string;
  pkg: string;
  targetLabel?: string;
  decision: DecisionType;
}

export type AwazStateKey = 'Idle' | 'Listening' | 'Acting' | 'NeedsHelp';

interface PhoneSimulatorProps {
  onLogEvent: (event: string, details?: any) => void;
  decisionLogs: DecisionLog[];
  onAddDecisionLog: (log: DecisionLog) => void;
}

export const PhoneSimulator: React.FC<PhoneSimulatorProps> = ({
  onLogEvent,
  decisionLogs,
  onAddDecisionLog,
}) => {
  const [isDebugMode, setIsDebugMode] = useState<boolean>(true);
  const [isA11yEnabled, setIsA11yEnabled] = useState<boolean>(true);
  const [currentState, setCurrentState] = useState<AwazStateKey>('Idle');
  const [showDebugScreen, setShowDebugScreen] = useState<boolean>(false);

  // Foreground App Simulator (allows testing "switch apps during countdown")
  const [simulatedForegroundPkg, setSimulatedForegroundPkg] = useState<string>('com.android.settings');

  // Overlay state
  const [showConfirmationOverlay, setShowConfirmationOverlay] = useState<boolean>(false);
  const [overlayTimeLeft, setOverlayTimeLeft] = useState<number>(20);
  const [lastOverlayResult, setLastOverlayResult] = useState<string | null>(null);

  // Screen Dump state
  const [isDumping, setIsDumping] = useState<boolean>(false);
  const [dumpCountdown, setDumpCountdown] = useState<number>(3);
  const [dumpStatusMessage, setDumpStatusMessage] = useState<string | null>(null);
  const [dumpedJson, setDumpedJson] = useState<string | null>(null);

  // Hardware Volume Up long press tracking (800ms)
  const [volUpPressing, setVolUpPressing] = useState<boolean>(false);
  const [volUpProgress, setVolUpProgress] = useState<number>(0);
  const volUpTimerRef = useRef<NodeJS.Timeout | null>(null);
  const volUpStartRef = useRef<number>(0);

  // State configurations matching Material 3 AwazState colors
  const stateConfigs: Record<
    AwazStateKey,
    { bg: string; btn: string; iconColor: string; icon: React.ReactNode; pulse: boolean }
  > = {
    Idle: {
      bg: 'bg-slate-900',
      btn: 'bg-slate-800 hover:bg-slate-700 active:scale-95',
      iconColor: 'text-slate-200',
      icon: <MicOff className="w-28 h-28" />,
      pulse: false,
    },
    Listening: {
      bg: 'bg-blue-950',
      btn: 'bg-blue-600 shadow-blue-500/50 shadow-2xl active:scale-95 animate-pulse',
      iconColor: 'text-blue-200',
      icon: <Mic className="w-28 h-28" />,
      pulse: true,
    },
    Acting: {
      bg: 'bg-emerald-950',
      btn: 'bg-emerald-600 shadow-emerald-500/50 shadow-2xl active:scale-95 animate-pulse',
      iconColor: 'text-emerald-200',
      icon: <Brain className="w-28 h-28" />,
      pulse: true,
    },
    NeedsHelp: {
      bg: 'bg-red-950',
      btn: 'bg-red-600 shadow-red-500/50 shadow-2xl active:scale-95 animate-pulse',
      iconColor: 'text-red-200',
      icon: <AlertTriangle className="w-28 h-28" />,
      pulse: true,
    },
  };

  const handleStateCycle = () => {
    if (navigator.vibrate) navigator.vibrate(40);
    const order: AwazStateKey[] = ['Idle', 'Listening', 'Acting', 'NeedsHelp'];
    const nextState = order[(order.indexOf(currentState) + 1) % order.length];
    setCurrentState(nextState);
    onLogEvent(`State transition: ${currentState} -> ${nextState} (Haptic click emitted)`);
  };

  // 20-second Confirmation Overlay countdown
  useEffect(() => {
    let timer: NodeJS.Timeout;
    if (showConfirmationOverlay) {
      setOverlayTimeLeft(20);
      timer = setInterval(() => {
        setOverlayTimeLeft((prev) => {
          if (prev <= 1) {
            clearInterval(timer);
            setShowConfirmationOverlay(false);
            setLastOverlayResult('TIMEOUT (Counts as DENIED)');
            onLogEvent('ConfirmationOverlay TIMEOUT after 20 seconds -> Result: DENIED');
            return 0;
          }
          return prev - 1;
        });
      }, 1000);
    }
    return () => clearInterval(timer);
  }, [showConfirmationOverlay]);

  // Volume Up button long press simulation (800ms)
  const handleVolUpMouseDown = () => {
    setVolUpPressing(true);
    setVolUpProgress(0);
    volUpStartRef.current = Date.now();

    volUpTimerRef.current = setInterval(() => {
      const elapsed = Date.now() - volUpStartRef.current;
      const progress = Math.min(100, Math.floor((elapsed / 800) * 100));
      setVolUpProgress(progress);

      if (elapsed >= 800) {
        clearInterval(volUpTimerRef.current!);
        volUpTimerRef.current = null;
        setVolUpPressing(false);
        setVolUpProgress(0);
        if (navigator.vibrate) navigator.vibrate([60, 40, 60]);
        onLogEvent('Hardware Trigger Event: Volume Up held for >= 800ms -> KeyHoldDetector consumed press and fired trigger');
      }
    }, 40);
  };

  const handleVolUpMouseUp = () => {
    if (volUpTimerRef.current) {
      clearInterval(volUpTimerRef.current);
      volUpTimerRef.current = null;
      const elapsed = Date.now() - volUpStartRef.current;
      onLogEvent(`Volume Up released after ${elapsed}ms (normal volume event, not consumed by long-press)`);
    }
    setVolUpPressing(false);
    setVolUpProgress(0);
  };

  // 3-second screen dump with "switch app" rule
  const handleStartScreenDump = () => {
    if (isDumping) return;
    setIsDumping(true);
    setDumpCountdown(3);
    setDumpStatusMessage('Switch apps during the 3s countdown...');
    setDumpedJson(null);
    onLogEvent('Debug: Screen dump initiated. Waiting 3 seconds for tester to switch apps...');

    const countdown = setInterval(() => {
      setDumpCountdown((prev) => {
        if (prev <= 1) {
          clearInterval(countdown);
          setIsDumping(false);
          performScreenDump();
          return 3;
        }
        return prev - 1;
      });
    }, 1000);
  };

  const performScreenDump = () => {
    // Rule F: "If the foreground app is com.awaz.app, show "switch app" and do not snapshot."
    if (simulatedForegroundPkg === 'com.awaz.app') {
      setDumpStatusMessage('switch app (AWAZ is still in the foreground)');
      setDumpedJson(null);
      onLogEvent('Dump screen blocked: foreground app is com.awaz.app ("switch app")');
      return;
    }

    const mockSnapshot = {
      timestamp: Date.now(),
      foregroundPackage: simulatedForegroundPkg,
      elements: [
        {
          id: 1,
          label: simulatedForegroundPkg.includes('settings') ? 'Wi-Fi & Network' : 'Chats',
          role: 'TextView',
          clickable: true,
          enabled: true,
          isPassword: false,
          boundsCenter: { x: 540, y: 320 },
        },
        {
          id: 2,
          label: simulatedForegroundPkg.includes('settings') ? 'Connected Devices' : 'Status',
          role: 'TextView',
          clickable: true,
          enabled: true,
          isPassword: false,
          boundsCenter: { x: 540, y: 440 },
        },
        {
          id: 3,
          label: simulatedForegroundPkg.includes('settings') ? 'Personal hotspot' : 'Calls',
          role: 'TextView',
          clickable: true,
          enabled: true,
          isPassword: false,
          boundsCenter: { x: 540, y: 560 },
        },
      ],
    };

    const jsonStr = JSON.stringify(mockSnapshot, null, 2);
    setDumpStatusMessage(`Captured snapshot of ${simulatedForegroundPkg}`);
    setDumpedJson(jsonStr);
    onLogEvent(`Logcat: [ScreenSnapshot] Dumped foreground package ${simulatedForegroundPkg} (3 visible nodes)`, mockSnapshot);
  };

  const currentCfg = stateConfigs[currentState];

  return (
    <div className="flex flex-col items-center">
      {/* Simulation Controls Bar */}
      <div className="w-full max-w-sm mb-4 space-y-2 bg-slate-800/90 border border-slate-700 px-3 py-2 rounded-xl text-xs">
        <div className="flex items-center justify-between gap-2">
          <div className="flex items-center gap-1.5">
            <span className="text-slate-400">Build:</span>
            <button
              onClick={() => {
                setIsDebugMode(!isDebugMode);
                setShowDebugScreen(false);
              }}
              className={`px-2 py-0.5 rounded font-mono font-semibold transition ${
                isDebugMode
                  ? 'bg-amber-500/20 text-amber-300 border border-amber-500/40'
                  : 'bg-emerald-500/20 text-emerald-300 border border-emerald-500/40'
              }`}
            >
              {isDebugMode ? 'DEBUG' : 'RELEASE'}
            </button>
          </div>

          <div className="flex items-center gap-1.5">
            <span className="text-slate-400">A11y:</span>
            <button
              onClick={() => {
                setIsA11yEnabled(!isA11yEnabled);
                onLogEvent(`Accessibility Service state: ${!isA11yEnabled ? 'CONNECTED' : 'DISCONNECTED'}`);
              }}
              className={`px-2 py-0.5 rounded font-medium transition ${
                isA11yEnabled ? 'bg-emerald-600 text-white' : 'bg-rose-600 text-white'
              }`}
            >
              {isA11yEnabled ? 'Active' : 'Disabled'}
            </button>
          </div>
        </div>

        {/* Foreground App Selector for 3s Dump Screen test */}
        <div className="flex items-center justify-between pt-1 border-t border-slate-700/60 text-[11px]">
          <span className="text-slate-400">Simulate Target App:</span>
          <select
            value={simulatedForegroundPkg}
            onChange={(e) => {
              setSimulatedForegroundPkg(e.target.value);
              onLogEvent(`Switched simulated foreground app to: ${e.target.value}`);
            }}
            className="bg-slate-950 text-slate-200 border border-slate-700 rounded px-1.5 py-0.5 font-mono text-[10px]"
          >
            <option value="com.android.settings">Settings (External)</option>
            <option value="com.whatsapp">WhatsApp (External)</option>
            <option value="com.awaz.app">com.awaz.app (Own App)</option>
          </select>
        </div>
      </div>

      {/* Android Device Shell Frame */}
      <div className="relative">
        {/* Physical Volume Up button on left bezel */}
        <div className="absolute -left-3 top-28 z-20 flex flex-col items-center">
          <button
            title="Press and hold 800ms for hardware trigger"
            onMouseDown={handleVolUpMouseDown}
            onMouseUp={handleVolUpMouseUp}
            onTouchStart={handleVolUpMouseDown}
            onTouchEnd={handleVolUpMouseUp}
            className={`w-3.5 h-16 rounded-l-md border-r-0 border transition-all ${
              volUpPressing
                ? 'bg-amber-500 border-amber-400 shadow-lg shadow-amber-500/50'
                : 'bg-slate-700 hover:bg-slate-600 border-slate-600'
            }`}
          />
          {volUpPressing && (
            <div className="absolute -top-7 -left-12 bg-slate-900 border border-amber-500 text-amber-300 px-2 py-0.5 rounded text-[10px] font-mono whitespace-nowrap shadow-xl">
              Hold {volUpProgress}%
            </div>
          )}
        </div>

        {/* Volume Down physical button */}
        <div className="absolute -left-3 top-48 z-20">
          <div className="w-3.5 h-16 rounded-l-md bg-slate-700 border-r-0 border border-slate-600" />
        </div>

        {/* Power physical button */}
        <div className="absolute -right-3 top-36 z-20">
          <div className="w-3.5 h-20 rounded-r-md bg-slate-700 border-l-0 border border-slate-600" />
        </div>

        {/* Main Phone Body */}
        <div className="relative w-[340px] h-[690px] bg-black rounded-[46px] p-3 shadow-2xl border-4 border-slate-700 ring-1 ring-slate-900 select-none overflow-hidden">
          <div className="relative w-full h-full rounded-[38px] overflow-hidden flex flex-col bg-slate-950">
            {/* Status Bar */}
            <div className="h-7 w-full bg-black/40 backdrop-blur-sm px-6 flex items-center justify-between text-[11px] text-slate-300 font-medium z-30">
              <span>12:00</span>
              <div className="w-3.5 h-3.5 rounded-full bg-black border border-slate-800" />
              <div className="flex items-center gap-1.5">
                <span className="text-[10px] font-semibold">5G</span>
                <div className="w-3 h-2 rounded-xs border border-slate-300 relative">
                  <div className="h-full bg-slate-300 w-3/4" />
                </div>
              </div>
            </div>

            {/* SCREEN VIEWPORT ROUTER */}
            <div className="relative flex-1 w-full overflow-hidden flex flex-col">
              {/* 1. First-Run Screen (if accessibility service disabled) */}
              {!isA11yEnabled ? (
                <div className="w-full h-full bg-slate-950 flex flex-col items-center justify-center p-6 text-center">
                  <div className="w-32 h-32 rounded-full bg-slate-900 border-2 border-slate-800 flex items-center justify-center mb-8 shadow-inner">
                    <Accessibility className="w-20 h-20 text-blue-400" />
                  </div>

                  <div className="animate-bounce mb-8">
                    <ArrowDown className="w-14 h-14 text-amber-400" />
                  </div>

                  <button
                    onClick={() => {
                      setIsA11yEnabled(true);
                      onLogEvent('FirstRun guide: Accessibility settings opened & service connected');
                    }}
                    className="w-48 h-20 rounded-3xl bg-blue-600 hover:bg-blue-500 active:scale-95 shadow-xl shadow-blue-600/40 flex items-center justify-center transition"
                  >
                    <Pointer className="w-12 h-12 text-white" />
                  </button>
                </div>
              ) : showDebugScreen && isDebugMode ? (
                /* 2. Debug Screen (DEBUG builds only) */
                <div className="w-full h-full bg-slate-900 flex flex-col p-4 text-left overflow-y-auto">
                  <div className="flex items-center justify-between mb-3 pb-2 border-b border-slate-800">
                    <div className="flex items-center gap-2">
                      <button
                        onClick={() => setShowDebugScreen(false)}
                        className="p-1 rounded-lg hover:bg-slate-800 text-slate-300"
                      >
                        <ArrowLeft className="w-5 h-5" />
                      </button>
                      <Bug className="w-5 h-5 text-amber-400" />
                      <span className="text-xs font-bold text-white">Debug & Diagnostics</span>
                    </div>
                  </div>

                  {/* Buttons: Dump Screen & Test Confirmation */}
                  <div className="grid grid-cols-2 gap-2 mb-3">
                    <button
                      onClick={handleStartScreenDump}
                      disabled={isDumping}
                      className="py-2.5 px-2 bg-blue-600 hover:bg-blue-500 rounded-xl text-white text-xs font-semibold flex items-center justify-center gap-1.5 shadow cursor-pointer"
                    >
                      <Camera className="w-4 h-4" />
                      {isDumping ? `Wait ${dumpCountdown}s...` : 'Dump Screen'}
                    </button>

                    <button
                      onClick={() => setShowConfirmationOverlay(true)}
                      className="py-2.5 px-2 bg-emerald-600 hover:bg-emerald-500 rounded-xl text-white text-xs font-semibold flex items-center justify-center gap-1.5 shadow cursor-pointer"
                    >
                      <CheckCircle className="w-4 h-4" />
                      Test Confirm
                    </button>
                  </div>

                  {dumpStatusMessage && (
                    <div
                      className={`mb-2 px-2.5 py-1.5 rounded-lg text-[11px] font-medium ${
                        dumpStatusMessage.startsWith('switch app')
                          ? 'bg-amber-950/80 border border-amber-700 text-amber-200'
                          : 'bg-slate-800 border border-slate-700 text-cyan-300'
                      }`}
                    >
                      {dumpStatusMessage}
                    </div>
                  )}

                  {lastOverlayResult && (
                    <div className="mb-2 px-2.5 py-1 bg-slate-800 border border-slate-700 rounded-lg text-[10px] text-emerald-300 font-mono">
                      {lastOverlayResult}
                    </div>
                  )}

                  {/* Dumped JSON View */}
                  {dumpedJson && (
                    <div className="mb-3">
                      <div className="text-[10px] text-slate-400 font-semibold mb-1">
                        Snapshot JSON (Logged to Logcat via kotlinx.serialization):
                      </div>
                      <pre className="p-2 bg-slate-950 rounded-lg text-[9px] text-cyan-300 font-mono overflow-x-auto max-h-32 border border-slate-800">
                        {dumpedJson}
                      </pre>
                    </div>
                  )}

                  {/* Recent PolicyEngine Decisions List */}
                  <div className="flex-1">
                    <div className="text-[10px] text-slate-400 font-semibold mb-1.5">
                      Recent Decisions ({decisionLogs.length}):
                    </div>
                    {decisionLogs.length === 0 ? (
                      <div className="p-3 text-center text-xs text-slate-500 bg-slate-950/50 rounded-lg border border-slate-800">
                        No policy decisions yet.
                      </div>
                    ) : (
                      <div className="space-y-1.5 max-h-44 overflow-y-auto pr-1">
                        {decisionLogs.map((log) => (
                          <div
                            key={log.id}
                            className={`p-2 rounded-lg border text-xs ${
                              log.decision.type === 'Allow'
                                ? 'bg-emerald-950/50 border-emerald-800/80 text-emerald-200'
                                : log.decision.type === 'RequireConfirmation'
                                ? 'bg-amber-950/50 border-amber-800/80 text-amber-200'
                                : log.decision.type === 'HandOffToHuman'
                                ? 'bg-purple-950/50 border-purple-800/80 text-purple-200'
                                : 'bg-red-950/50 border-red-800/80 text-red-200'
                            }`}
                          >
                            <div className="flex items-center justify-between font-bold text-[10px]">
                              <span>{log.decision.type}</span>
                              <span className="text-[9px] text-slate-400 font-normal">{log.timestamp}</span>
                            </div>
                            <div className="text-[10px] text-slate-300 truncate mt-0.5">
                              Pkg: {log.pkg}
                            </div>
                            {log.targetLabel && (
                              <div className="text-[10px] text-slate-300 truncate">
                                Target: "{log.targetLabel}"
                              </div>
                            )}
                          </div>
                        ))}
                      </div>
                    )}
                  </div>
                </div>
              ) : (
                /* 3. Main Voice Button Screen (ZERO TEXT - Pure Icons & Colors) */
                <div
                  className={`w-full h-full ${currentCfg.bg} flex items-center justify-center transition-colors duration-500 relative cursor-pointer`}
                  onClick={handleStateCycle}
                >
                  {/* Giant Central State Button: ZERO TEXT */}
                  <div
                    className={`w-56 h-56 rounded-full ${currentCfg.btn} flex items-center justify-center transition-all duration-300 transform shadow-2xl`}
                  >
                    <div className={`${currentCfg.iconColor} transition-transform`}>
                      {currentCfg.icon}
                    </div>
                  </div>

                  {/* Debug Button (DEBUG builds only, bottom right) */}
                  {isDebugMode && (
                    <button
                      onClick={(e) => {
                        e.stopPropagation();
                        setShowDebugScreen(true);
                        onLogEvent('Opened DebugScreen from floating button');
                      }}
                      className="absolute bottom-6 right-6 w-12 h-12 rounded-full bg-slate-800/90 border border-slate-700 text-amber-400 flex items-center justify-center shadow-lg hover:scale-105 active:scale-95 transition cursor-pointer"
                    >
                      <Bug className="w-6 h-6" />
                    </button>
                  )}
                </div>
              )}

              {/* 4. ConfirmationOverlay (TYPE_ACCESSIBILITY_OVERLAY Simulation with Classic Views) */}
              {showConfirmationOverlay && (
                <div className="absolute inset-0 z-50 bg-black/85 backdrop-blur-xs flex flex-col items-center justify-center p-6 animate-in fade-in duration-200">
                  <div className="flex flex-col items-center gap-10">
                    {/* Giant Green Tick (CONFIRMED) */}
                    <button
                      onClick={() => {
                        setShowConfirmationOverlay(false);
                        setLastOverlayResult('CONFIRMED');
                        onLogEvent('ConfirmationOverlay completed: CONFIRMED');
                      }}
                      className="w-32 h-32 rounded-full bg-emerald-500 hover:bg-emerald-400 active:scale-90 shadow-2xl shadow-emerald-500/50 flex items-center justify-center transition cursor-pointer"
                    >
                      <Check className="w-20 h-20 text-white stroke-[4]" />
                    </button>

                    {/* Giant Red Cross (DENIED) */}
                    <button
                      onClick={() => {
                        setShowConfirmationOverlay(false);
                        setLastOverlayResult('DENIED');
                        onLogEvent('ConfirmationOverlay completed: DENIED');
                      }}
                      className="w-32 h-32 rounded-full bg-red-600 hover:bg-red-500 active:scale-90 shadow-2xl shadow-red-600/50 flex items-center justify-center transition cursor-pointer"
                    >
                      <X className="w-20 h-20 text-white stroke-[4]" />
                    </button>
                  </div>

                  {/* 20-second timeout auto-deny */}
                  <div className="absolute bottom-8 flex items-center gap-1.5 text-slate-400 text-xs font-mono">
                    <Clock className="w-3.5 h-3.5 text-amber-400" />
                    <span>{overlayTimeLeft}s auto-deny</span>
                  </div>
                </div>
              )}
            </div>

            {/* Android Navigation Bar */}
            <div className="h-5 w-full bg-black/80 flex items-center justify-center pb-1">
              <div className="w-28 h-1 rounded-full bg-slate-600" />
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};

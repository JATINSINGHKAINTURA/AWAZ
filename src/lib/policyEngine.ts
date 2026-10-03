/**
 * Exact TypeScript mirror of PolicyEngine.kt for browser verification and interactive testing.
 * Implements token-based Unicode NFKC normalization and package segment heuristics.
 */

export interface PolicyRulesTS {
  deniedPackages: Set<string>;
  deniedPackageFragments: Set<string>;
  sensitiveTerms: Set<string>;
  confirmationTerms: Set<string>;
}

export type DecisionType =
  | { type: 'Allow' }
  | { type: 'RequireConfirmation'; code: string }
  | { type: 'Block'; code: string }
  | { type: 'HandOffToHuman'; code: string };

export interface ScreenElement {
  id: number;
  label: string;
  role?: string;
  clickable?: boolean;
  enabled?: boolean;
  checked?: boolean | null;
  isPassword?: boolean;
  boundsCenterX?: number;
  boundsCenterY?: number;
}

export interface AgentAction {
  type: 'Click' | 'LongClick' | 'ScrollForward' | 'ScrollBackward' | 'GlobalBack' | 'GlobalHome' | 'GlobalRecents' | 'SendMessage' | 'MakeCall';
  targetElementId?: number;
  targetLabel?: string;
  timestampMs?: number;
}

export interface PolicyContext {
  foregroundPackage: string;
  elements: ScreenElement[];
  actionsTakenCount: number;
  lastAction?: AgentAction;
  consecutiveIdenticalActionCount: number;
  lastActionTimestampMs: number;
}

export class PolicyEngineTS {
  private rules: PolicyRulesTS;
  private normalizedSensitiveTerms: string[][];
  private normalizedConfirmationTerms: string[][];

  constructor(customRules?: PolicyRulesTS) {
    this.rules = customRules ?? {
      deniedPackages: new Set([
        'com.phonepe.app',
        'com.google.android.apps.nbu.paisa.user',
        'net.one97.paytm',
        'in.org.npci.upiapp'
      ]),
      deniedPackageFragments: new Set(['bank', 'upi', 'wallet', 'pay']),
      sensitiveTerms: new Set([
        'OTP',
        'ओटीपी',
        'PIN',
        'पिन',
        'CVV',
        'password',
        'पासवर्ड',
        'verification code',
        'UPI PIN',
        'एटीएम'
      ]),
      confirmationTerms: new Set([
        'send', 'call', 'share', 'delete', 'remove', 'uninstall',
        'clear data', 'grant', 'permission', 'factory reset', 'reset',
        'purchase', 'pay', 'install', 'buy',
        'भेजें', 'भेजो', 'कॉल', 'फ़ोन', 'शेयर', 'हटाएं', 'हटाओ', 'मिटाएं',
        'अनइन्स्टॉल', 'डेटा साफ़', 'अनुमति', 'रीसेट', 'खरीदें', 'खरीदो', 'भुगतान', 'पैसे',
        'bhejo', 'bhej', 'call karo', 'phone karo', 'share karo', 'hatao',
        'delete karo', 'uninstall karo', 'clear karo', 'permission do',
        'khareedo', 'pay karo', 'payment'
      ])
    };

    this.normalizedSensitiveTerms = Array.from(this.rules.sensitiveTerms)
      .map((term) => PolicyEngineTS.tokenize(term))
      .filter((t) => t.length > 0);

    this.normalizedConfirmationTerms = Array.from(this.rules.confirmationTerms)
      .map((term) => PolicyEngineTS.tokenize(term))
      .filter((t) => t.length > 0);
  }

  evaluatePackage(packageName: string): DecisionType | null {
    const lowerPkg = packageName.toLowerCase();
    const isExplicitlyDenylisted = Array.from(this.rules.deniedPackages).some(
      (pkg) => pkg.toLowerCase() === lowerPkg
    );

    const segments = lowerPkg.split('.');
    const isHeuristicFinancialPkg = segments.some((seg) =>
      Array.from(this.rules.deniedPackageFragments).some((frag) =>
        seg.includes(frag.toLowerCase())
      )
    );

    if (isExplicitlyDenylisted || isHeuristicFinancialPkg) {
      return { type: 'HandOffToHuman', code: 'BLOCKED_FINANCIAL_PACKAGE' };
    }
    return null;
  }

  evaluate(ctx: PolicyContext, action: AgentAction): DecisionType {
    const pkgDecision = this.evaluatePackage(ctx.foregroundPackage);
    if (pkgDecision) {
      return pkgDecision;
    }

    const timestamp = action.timestampMs ?? Date.now();

    // 1. Action Limit: Maximum 12 actions per task
    if (ctx.actionsTakenCount >= 12) {
      return { type: 'Block', code: 'STEP_LIMIT' };
    }

    // 2. Minimum 300 ms between gestures
    if (ctx.lastActionTimestampMs > 0 && timestamp - ctx.lastActionTimestampMs < 300) {
      return { type: 'Block', code: 'RATE_LIMIT_300MS' };
    }

    // 3. Maximum 2 identical consecutive actions
    if (
      ctx.consecutiveIdenticalActionCount >= 2 &&
      ctx.lastAction &&
      this.isSameAction(ctx.lastAction, action)
    ) {
      return { type: 'Block', code: 'CONSECUTIVE_ACTION_LIMIT' };
    }

    // 4. Package Denylist & Financial heuristics applied to individual dot-separated package segments
    const lowerPkg = ctx.foregroundPackage.toLowerCase();
    const isExplicitlyDenylisted = Array.from(this.rules.deniedPackages).some(
      (pkg) => pkg.toLowerCase() === lowerPkg
    );

    const segments = lowerPkg.split('.');
    const isHeuristicFinancialPkg = segments.some((seg) =>
      Array.from(this.rules.deniedPackageFragments).some((frag) =>
        seg.includes(frag.toLowerCase())
      )
    );

    if (isExplicitlyDenylisted || isHeuristicFinancialPkg) {
      return { type: 'HandOffToHuman', code: 'BLOCKED_FINANCIAL_PACKAGE' };
    }

    // 5. Password field detection
    const hasPasswordField = ctx.elements.some((el) => el.isPassword);

    // 6. Sensitive terms matching (TOKEN-based matching, NFKC normalized)
    const matchedTerm = this.findSensitiveTermOnScreen(ctx.elements);
    if (matchedTerm) {
      return { type: 'HandOffToHuman', code: `SENSITIVE_TERM_${matchedTerm}` };
    }

    if (hasPasswordField) {
      return { type: 'Block', code: 'PASSWORD_FIELD_DETECTED' };
    }

    // 7. RequireConfirmation for high-impact actions
    const actionTypeNeedsConfirmation =
      action.type === 'SendMessage' || action.type === 'MakeCall';

    const targetLabelNeedsConfirmation = action.targetLabel
      ? this.containsTokenSequence(
          PolicyEngineTS.tokenize(action.targetLabel),
          this.normalizedConfirmationTerms
        )
      : false;

    if (actionTypeNeedsConfirmation || targetLabelNeedsConfirmation) {
      return { type: 'RequireConfirmation', code: 'USER_CONFIRMATION_REQUIRED' };
    }

    // 8. Normal, safe screen action
    return { type: 'Allow' };
  }

  private findSensitiveTermOnScreen(elements: ScreenElement[]): string | null {
    for (const el of elements) {
      const labelTokens = PolicyEngineTS.tokenize(el.label);
      for (const termTokens of this.normalizedSensitiveTerms) {
        if (this.matchesTokens(labelTokens, termTokens)) {
          return termTokens.join('_');
        }
      }
    }
    return null;
  }

  private isSameAction(a: AgentAction, b: AgentAction): boolean {
    if (a.type !== b.type) return false;
    if (a.targetElementId !== b.targetElementId) return false;
    return a.targetLabel === b.targetLabel;
  }

  private matchesTokens(labelTokens: string[], targetTokens: string[]): boolean {
    if (targetTokens.length === 0 || targetTokens.length > labelTokens.length) return false;
    const targetSize = targetTokens.length;
    for (let i = 0; i <= labelTokens.length - targetSize; i++) {
      let match = true;
      for (let j = 0; j < targetSize; j++) {
        if (labelTokens[i + j] !== targetTokens[j]) {
          match = false;
          break;
        }
      }
      if (match) return true;
    }
    return false;
  }

  private containsTokenSequence(labelTokens: string[], sequenceList: string[][]): boolean {
    return sequenceList.some((seq) => this.matchesTokens(labelTokens, seq));
  }

  static tokenize(input: string): string[] {
    const normalized = input.normalize('NFKC').toLowerCase().trim();
    // Split on any character that is not a letter, mark, or digit
    // In JS regex: \p{L}, \p{M}, \p{Nd} with 'u' flag
    return normalized
      .split(/[^\p{L}\p{M}\p{Nd}]+/u)
      .filter((t) => t.length > 0);
  }
}

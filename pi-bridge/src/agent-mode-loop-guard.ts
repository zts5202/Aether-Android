export const AGENT_MODE_LOOP_STOP_MESSAGE =
  "Stopped: the same check ran again with no progress. Do not re-read logs or repeat this tool call. Tell the user the current state and stop.";

export interface AgentModeLoopDecision {
  stop: boolean;
  message: string;
}

const INSPECTION_TOOLS = new Set(["read", "bash", "grep", "find", "ls"]);
const INSPECTION_ACTIONS = new Set(["status", "list_apps", "find_text", "screenshot"]);
const ACTION_PATTERN = /"action"\s*:\s*"([^"]+)"/;

const ALLOW = { stop: false, message: "" };

/**
 * Stops an Agent Mode turn that keeps reading the same logs or repeating a check with no new result.
 * A gesture or a launch counts as progress and clears the streak.
 * Matches the Kotlin AgentModeLoopGuard used by the unit tests.
 */
export class AgentModeLoopGuard {
  private lastSignature: string | null = null;
  private streak = 0;
  private lastOutput: string | null = null;
  private unchangedResults = 0;
  private stopped = false;

  reset(): void {
    this.lastSignature = null;
    this.streak = 0;
    this.lastOutput = null;
    this.unchangedResults = 0;
    this.stopped = false;
  }

  beforeCall(toolName: string, args: string): AgentModeLoopDecision {
    if (this.stopped) return this.stopDecision();
    if (!this.isInspection(toolName, args)) {
      this.lastSignature = null;
      this.streak = 0;
      this.lastOutput = null;
      this.unchangedResults = 0;
      return ALLOW;
    }
    if (this.unchangedResults >= 2) return this.stopDecision();
    const signature = `${toolName.trim()}\n${args.trim()}`;
    this.streak = signature === this.lastSignature ? this.streak + 1 : 1;
    this.lastSignature = signature;
    if (this.streak >= 3) return this.stopDecision();
    return ALLOW;
  }

  afterResult(toolName: string, args: string, output: string): AgentModeLoopDecision {
    if (this.stopped) return this.stopDecision();
    if (!this.isInspection(toolName, args)) return ALLOW;
    const fingerprint = output.trim().slice(0, 240);
    if (fingerprint.length < 40) return ALLOW;
    this.unchangedResults = fingerprint === this.lastOutput ? this.unchangedResults + 1 : 1;
    this.lastOutput = fingerprint;
    if (this.unchangedResults >= 3) return this.stopDecision();
    return ALLOW;
  }

  private stopDecision(): AgentModeLoopDecision {
    this.stopped = true;
    return { stop: true, message: AGENT_MODE_LOOP_STOP_MESSAGE };
  }

  private isInspection(toolName: string, args: string): boolean {
    const name = toolName.trim();
    if (INSPECTION_TOOLS.has(name)) return true;
    if (name !== "agent_display") return false;
    const action = ACTION_PATTERN.exec(args)?.[1];
    if (!action) return true;
    return INSPECTION_ACTIONS.has(action);
  }
}

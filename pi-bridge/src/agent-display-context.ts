import type { AgentMessage } from "@earendil-works/pi-agent-core/node";
import { estimateTokens, type ExtensionFactory } from "@earendil-works/pi-coding-agent";

// Screen-observation history pruning for Agent Mode.
//
// Every model request resends the whole conversation, so each screenshot and OCR element list an
// agent_display call returned is resent on every later step. Only the most recent observations
// matter for deciding the next gesture, so older ones are replaced with a short placeholder in the
// request (the session file and UI keep the originals).
//
// Rewriting history invalidates the provider prompt cache from the first changed message, and a
// cache miss costs far more than resending cached history (DeepSeek bills cache hits at ~1/50 of a
// miss). So pruning stays off until the unpruned context is large, and then moves in batches: the
// pruned set only grows every `batchSize` new observations, keeping the prefix byte-identical in
// between. The threshold is measured on the unpruned history, which only grows, so pruning never
// toggles off again and re-breaks the cache.

export interface ObservationPruneOptions {
  keepRecentScreenshots: number;
  keepRecentElementSets: number;
  batchSize: number;
  minContextTokens: number;
}

export const DefaultObservationPruneOptions: ObservationPruneOptions = {
  keepRecentScreenshots: 2,
  keepRecentElementSets: 2,
  batchSize: 6,
  minContextTokens: 60_000,
};

export type MessageTokenEstimator = (message: AgentMessage) => number;

const ScreenshotToolNames = new Set(["agent_display", "browser"]);
const ElementToolName = "agent_display";
const ElementKeys = ["elements", "matches"];

export const OmittedScreenshotPlaceholder =
  "[Earlier screenshot omitted from context to save tokens. It is out of date; " +
  "rely on the latest screenshot or call action=screenshot.]";

interface ContentPart {
  type: string;
  text?: string;
}

interface ToolResultLike {
  role: "toolResult";
  toolName: string;
  content: ContentPart[];
}

function isToolResult(message: AgentMessage): message is AgentMessage & ToolResultLike {
  const candidate = message as unknown as Partial<ToolResultLike>;
  return candidate.role === "toolResult" &&
    typeof candidate.toolName === "string" &&
    Array.isArray(candidate.content);
}

export function prunedObservationCount(total: number, keepRecent: number, batchSize: number): number {
  const excess = total - Math.max(0, keepRecent);
  if (excess <= 0) return 0;
  const batch = Math.max(1, Math.floor(batchSize));
  return Math.floor(excess / batch) * batch;
}

function parseJsonObject(text: string | undefined): Record<string, unknown> | undefined {
  if (!text) return undefined;
  const trimmed = text.trimStart();
  if (!trimmed.startsWith("{")) return undefined;
  try {
    const parsed: unknown = JSON.parse(trimmed);
    return parsed && typeof parsed === "object" && !Array.isArray(parsed)
      ? parsed as Record<string, unknown>
      : undefined;
  } catch {
    return undefined;
  }
}

function hasElementSet(part: ContentPart): boolean {
  if (part.type !== "text") return false;
  const parsed = parseJsonObject(part.text);
  return Boolean(parsed && ElementKeys.some((key) => Array.isArray(parsed[key])));
}

function withoutElementSet(part: ContentPart): ContentPart {
  const parsed = parseJsonObject(part.text);
  if (!parsed) return part;
  let omitted = 0;
  for (const key of ElementKeys) {
    const value = parsed[key];
    if (!Array.isArray(value)) continue;
    omitted = Math.max(omitted, value.length);
    delete parsed[key];
  }
  parsed.elements_omitted = omitted;
  return { ...part, text: JSON.stringify(parsed) };
}

/**
 * Returns the messages with all but the most recent screenshots and OCR element sets replaced by
 * placeholders. Messages are never mutated; untouched ones are returned by reference.
 */
export function pruneScreenObservations(
  messages: AgentMessage[],
  options: ObservationPruneOptions = DefaultObservationPruneOptions,
  estimate: MessageTokenEstimator = estimateTokens,
): AgentMessage[] {
  if (options.minContextTokens > 0) {
    let contextTokens = 0;
    for (const message of messages) {
      contextTokens += estimate(message);
      if (contextTokens >= options.minContextTokens) break;
    }
    if (contextTokens < options.minContextTokens) return messages;
  }
  const screenshotLocations: Array<[number, number]> = [];
  const elementLocations: Array<[number, number]> = [];
  messages.forEach((message, messageIndex) => {
    if (!isToolResult(message)) return;
    const tracksScreenshots = ScreenshotToolNames.has(message.toolName);
    const tracksElements = message.toolName === ElementToolName;
    if (!tracksScreenshots && !tracksElements) return;
    message.content.forEach((part, partIndex) => {
      if (tracksScreenshots && part.type === "image") screenshotLocations.push([messageIndex, partIndex]);
      if (tracksElements && hasElementSet(part)) elementLocations.push([messageIndex, partIndex]);
    });
  });

  const replacements = new Map<number, Map<number, ContentPart>>();
  const replace = (messageIndex: number, partIndex: number, part: ContentPart) => {
    let parts = replacements.get(messageIndex);
    if (!parts) {
      parts = new Map();
      replacements.set(messageIndex, parts);
    }
    parts.set(partIndex, part);
  };

  const prunedScreenshots = prunedObservationCount(
    screenshotLocations.length,
    options.keepRecentScreenshots,
    options.batchSize,
  );
  for (const [messageIndex, partIndex] of screenshotLocations.slice(0, prunedScreenshots)) {
    replace(messageIndex, partIndex, { type: "text", text: OmittedScreenshotPlaceholder });
  }

  const prunedElementSets = prunedObservationCount(
    elementLocations.length,
    options.keepRecentElementSets,
    options.batchSize,
  );
  for (const [messageIndex, partIndex] of elementLocations.slice(0, prunedElementSets)) {
    const message = messages[messageIndex] as unknown as ToolResultLike;
    replace(messageIndex, partIndex, withoutElementSet(message.content[partIndex]));
  }

  if (replacements.size === 0) return messages;
  return messages.map((message, messageIndex) => {
    const parts = replacements.get(messageIndex);
    if (!parts) return message;
    const toolResult = message as unknown as ToolResultLike;
    return {
      ...toolResult,
      content: toolResult.content.map((part, partIndex) => parts.get(partIndex) ?? part),
    } as unknown as AgentMessage;
  });
}

export const agentDisplayContextExtensionFactory: ExtensionFactory = (pi) => {
  pi.on("context", (event) => {
    const pruned = pruneScreenObservations(event.messages);
    return pruned === event.messages ? undefined : { messages: pruned };
  });
};

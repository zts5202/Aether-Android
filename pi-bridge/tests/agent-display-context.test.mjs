import assert from "node:assert/strict";
import { test } from "node:test";
import {
  OmittedScreenshotPlaceholder,
  pruneScreenObservations,
  prunedObservationCount,
} from "../src/agent-display-context.ts";

const options = { keepRecentScreenshots: 2, keepRecentElementSets: 2, batchSize: 3, minContextTokens: 0 };

function observation(step, { image = true, elements = true, toolName = "agent_display" } = {}) {
  const output = { ok: true, action: "tap", step };
  if (elements) output.elements = [{ text: `item ${step}`, bbox_norm: [1, 2, 3, 4] }];
  const content = [{ type: "text", text: JSON.stringify(output) }];
  if (image) content.push({ type: "image", mimeType: "image/jpeg", data: `jpeg-${step}` });
  return { role: "toolResult", toolCallId: `call-${step}`, toolName, content, isError: false, timestamp: step };
}

function imageData(messages) {
  return messages.flatMap((message) => (message.content ?? [])
    .filter((part) => part.type === "image")
    .map((part) => part.data));
}

function elementSteps(messages) {
  return messages
    .filter((message) => message.role === "toolResult")
    .map((message) => JSON.parse(message.content[0].text))
    .filter((output) => Array.isArray(output.elements))
    .map((output) => output.step);
}

test("pruned count only advances in whole batches beyond the kept window", () => {
  assert.deepEqual(
    [0, 1, 2, 3, 4, 5, 6, 7, 8].map((total) => prunedObservationCount(total, 2, 3)),
    [0, 0, 0, 0, 0, 3, 3, 3, 6],
  );
});

test("keeps everything until a full batch can be pruned", () => {
  const messages = [1, 2, 3, 4].map((step) => observation(step));
  assert.equal(pruneScreenObservations(messages, options), messages);
});

test("replaces the oldest screenshots and element sets with placeholders", () => {
  const messages = [
    { role: "user", content: [{ type: "image", mimeType: "image/png", data: "user-upload" }], timestamp: 0 },
    ...[1, 2, 3, 4, 5, 6].map((step) => observation(step)),
  ];
  const pruned = pruneScreenObservations(messages, options);
  assert.deepEqual(imageData(pruned), ["user-upload", "jpeg-4", "jpeg-5", "jpeg-6"]);
  assert.deepEqual(elementSteps(pruned), [4, 5, 6]);
  const first = pruned[1];
  assert.equal(first.content[1].text, OmittedScreenshotPlaceholder);
  assert.equal(JSON.parse(first.content[0].text).elements_omitted, 1);
  assert.equal(pruned[0], messages[0]);
  assert.equal(pruned[6], messages[6]);
  assert.equal(messages[1].content[1].data, "jpeg-1");
});

test("prefix stays identical between batches so prompt caches keep hitting", () => {
  const history = [1, 2, 3, 4, 5, 6, 7].map((step) => observation(step));
  const atSix = pruneScreenObservations(history.slice(0, 6), options);
  const atSeven = pruneScreenObservations(history, options);
  assert.deepEqual(atSeven.slice(0, 6), atSix);
});

test("an unchanged-screen result does not push out the screenshot still on screen", () => {
  const messages = [
    ...[1, 2, 3, 4].map((step) => observation(step)),
    ...[5, 6, 7].map((step) => observation(step, { image: false })),
  ];
  const pruned = pruneScreenObservations(messages, options);
  assert.deepEqual(imageData(pruned), ["jpeg-1", "jpeg-2", "jpeg-3", "jpeg-4"]);
  assert.deepEqual(elementSteps(pruned), [4, 5, 6, 7]);
});

test("leaves small contexts untouched so the prompt cache is never invalidated", () => {
  const messages = [1, 2, 3, 4, 5, 6].map((step) => observation(step));
  const gated = { ...options, minContextTokens: 1_000 };
  assert.equal(pruneScreenObservations(messages, gated, () => 100), messages);
  assert.notEqual(pruneScreenObservations(messages, gated, () => 200), messages);
});

test("default options keep a typical short Agent Mode task unpruned", () => {
  const messages = Array.from({ length: 20 }, (_, index) => observation(index + 1));
  assert.equal(pruneScreenObservations(messages), messages);
});

test("ignores tool results from unrelated tools", () => {
  const messages = [1, 2, 3, 4, 5, 6].map((step) => observation(step, { toolName: "read" }));
  assert.equal(pruneScreenObservations(messages, options), messages);
});

import assert from "node:assert/strict";
import { test } from "node:test";
import { AGENT_MODE_LOOP_STOP_MESSAGE, AgentModeLoopGuard } from "../src/agent-mode-loop-guard.ts";

test("third identical read stops before it runs", () => {
  const guard = new AgentModeLoopGuard();
  const args = JSON.stringify({ path: "/session/log.txt" });
  assert.equal(guard.beforeCall("read", args).stop, false);
  assert.equal(guard.beforeCall("read", args).stop, false);
  const third = guard.beforeCall("read", args);
  assert.equal(third.stop, true);
  assert.equal(third.message, AGENT_MODE_LOOP_STOP_MESSAGE);
  assert.equal(guard.beforeCall("bash", JSON.stringify({ command: "ls" })).stop, true);
});

test("unchanged inspection results stop the next check", () => {
  const guard = new AgentModeLoopGuard();
  const output = "session log line ".repeat(6);
  assert.ok(output.length >= 40);
  assert.equal(guard.beforeCall("bash", JSON.stringify({ command: "tail log" })).stop, false);
  assert.equal(guard.afterResult("bash", JSON.stringify({ command: "tail log" }), output).stop, false);
  assert.equal(guard.beforeCall("read", JSON.stringify({ path: "log" })).stop, false);
  assert.equal(guard.afterResult("read", JSON.stringify({ path: "log" }), output).stop, false);
  const next = guard.beforeCall("grep", JSON.stringify({ pattern: "发送" }));
  assert.equal(next.stop, true);
  assert.equal(next.message, AGENT_MODE_LOOP_STOP_MESSAGE);
});

test("a gesture resets the inspection streak", () => {
  const guard = new AgentModeLoopGuard();
  const args = JSON.stringify({ path: "/session/log.txt" });
  assert.equal(guard.beforeCall("read", args).stop, false);
  assert.equal(guard.beforeCall("read", args).stop, false);
  assert.equal(
    guard.beforeCall("agent_display", JSON.stringify({ action: "find_and_tap", query: "发送" })).stop,
    false,
  );
  assert.equal(guard.beforeCall("read", args).stop, false);
});

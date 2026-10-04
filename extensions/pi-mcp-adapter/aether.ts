import { chmodSync, existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";
import stripJsonComments from "strip-json-comments";
import { getAgentPath } from "./agent-dir.ts";
import {
  attachMcpAetherApi,
  readMcpAetherBridge,
  type McpAetherBridge,
  type McpAetherServerSnapshot,
  type McpAetherSnapshot,
} from "./aether-bridge.ts";

type AetherJsonObject = Record<string, unknown>;
type AetherView = AetherJsonObject | AetherView[] | string | null | undefined;
type AetherRenderContext = AetherJsonObject & { storage: AetherJsonObject };

export interface AetherSettingOption {
  value: string;
  label: string;
}

export interface AetherSettingActionItem {
  label: string;
  action: string;
  args?: AetherJsonObject;
  category?: string;
  tone?: "primary" | "neutral" | "danger";
  enabled?: boolean;
}

export interface AetherSettingDetailItem {
  label: string;
  value: string;
}

export interface AetherSettingDefinition {
  id: string;
  label?: string;
  title?: string;
  description?: string;
  subtitle?: string;
  tag?: string;
  pill?: string;
  badge?: string;
  type?:
    | "text"
    | "password"
    | "textarea"
    | "number"
    | "toggle"
    | "select"
    | "dropdown"
    | "segmented"
    | "tab"
    | "tabs"
    | "slider"
    | "button"
    | "link"
    | "label"
    | "divider"
    | "spacer"
    | "item-card"
    | "card"
    | "empty-state"
    | "choice"
    | "radio"
    | "action-row"
    | "chips"
    | "detail-line"
    | "key-value"
    | "pill"
    | "badge"
    | "result-card"
    | "callout";
  default?: string | number | boolean;
  placeholder?: string;
  options?: AetherSettingOption[];
  min?: number;
  max?: number;
  step?: number;
  action?: string;
  args?: AetherJsonObject;
  category?: string;
  url?: string;
  icon?: string;
  tone?: "primary" | "neutral" | "danger";
  enabled?: boolean;
  checked?: boolean;
  selected?: boolean;
  toggleAction?: string;
  editAction?: string;
  editCategory?: string;
  editArgs?: AetherJsonObject;
  deleteAction?: string;
  deleteArgs?: AetherJsonObject;
  expanded?: boolean;
  actions?: AetherSettingActionItem[];
  details?: AetherSettingDetailItem[];
  resultText?: string;
  result?: string;
  buttonLabel?: string;
  multiline?: boolean;
  secret?: boolean;
  settings?: AetherSettingDefinition[];
}

export interface AetherSettingsSection {
  id?: string;
  title?: string;
  description?: string;
  settings: AetherSettingDefinition[];
}

export interface AetherSettingsCategory {
  id: string;
  title: string;
  subtitle?: string;
  icon?: string;
  order?: number;
  trailingIcon?: string;
  trailingAction?: string;
  trailingCategory?: string;
  trailingArgs?: AetherJsonObject;
  hidden?: boolean;
  sections: AetherSettingsSection[];
}

export interface AetherSettingsDefinition {
  id: string;
  title: string;
  subtitle?: string;
  icon?: string;
  order?: number;
  trailingIcon?: string;
  trailingAction?: string;
  trailingCategory?: string;
  trailingArgs?: AetherJsonObject;
  sections?: AetherSettingsSection[];
  categories?: AetherSettingsCategory[];
}

export interface AetherExtensionAPI {
  ui: {
    node(type: string, properties?: AetherJsonObject, children?: AetherView[]): AetherJsonObject;
    text(text: string, properties?: AetherJsonObject): AetherJsonObject;
    column(children: AetherView[], properties?: AetherJsonObject): AetherJsonObject;
    row(children: AetherView[], properties?: AetherJsonObject): AetherJsonObject;
    card(children: AetherView[], properties?: AetherJsonObject): AetherJsonObject;
    button(label: string, action: string, properties?: AetherJsonObject): AetherJsonObject;
  };
  host: { invoke(method: string, args?: AetherJsonObject): Promise<AetherJsonObject> };
  storage: {
    get<T = unknown>(key: string, fallback?: T): T;
    set(key: string, value: unknown): void;
    delete(key: string): void;
    snapshot(): AetherJsonObject;
  };
  messages: { append(type: string, payload?: AetherJsonObject, text?: string): Promise<AetherJsonObject> };
  registerSettings(definition: AetherSettingsDefinition): () => void;
  registerAction(id: string, handler: (payload: AetherJsonObject) => unknown | Promise<unknown>): () => void;
  registerToolTitle?(toolName: string, runningTitle: string, completedTitle: string, priority?: number): () => void;
  invalidate(): void;
  notify(message: string, level?: "info" | "warning" | "error"): void;
}

const PAGE_ID = "mcp-settings";
const BRIDGE_OAUTH_KEY = "mcp:oauth-pending";
const settingStorageKey = (settingId: string) => `settings:${PAGE_ID}:${settingId}`;

type Transport = "stdio" | "http" | "socket";

const TRANSPORT_OPTIONS = [
  { value: "stdio", label: "标准输入输出（命令）" },
  { value: "http", label: "HTTP（Streamable HTTP / SSE）" },
  { value: "socket", label: "Unix 套接字（rmcp-mux）" },
] as const;

const LIFECYCLE_OPTIONS = [
  { value: "lazy", label: "按需连接（首次使用时）" },
  { value: "eager", label: "立即连接（会话开始时）" },
  { value: "keep-alive", label: "保持连接" },
  { value: "lazy-keep-alive", label: "按需连接，之后保持" },
] as const;

const PROTOCOL_OPTIONS = [
  { value: "legacy", label: "旧版（默认）" },
  { value: "auto", label: "自动（2026，失败则回退旧版）" },
  { value: "2026-07-28", label: "仅 2026-07-28" },
] as const;

const HTTP_TRANSPORT_OPTIONS = [
  { value: "auto", label: "自动（Streamable HTTP，失败则回退 SSE）" },
  { value: "streamable-http", label: "Streamable HTTP" },
  { value: "sse", label: "SSE" },
] as const;

const AUTH_OPTIONS = [
  { value: "auto", label: "自动（可用时使用 OAuth）" },
  { value: "oauth", label: "OAuth" },
  { value: "bearer", label: "Bearer Token" },
  { value: "none", label: "无" },
] as const;

const BOOLEAN_OPTIONS = [
  { value: "default", label: "默认" },
  { value: "true", label: "是" },
  { value: "false", label: "否" },
] as const;

const TOOL_PREFIX_OPTIONS = [
  { value: "unset", label: "使用全局设置" },
  { value: "server", label: "server（server__tool）" },
  { value: "short", label: "short（server_tool）" },
  { value: "none", label: "none（原始名称）" },
  { value: "mcp", label: "mcp（mcp__tool）" },
] as const;

const DIRECT_TOOLS_OPTIONS = [
  { value: "unset", label: "使用全局设置" },
  { value: "all", label: "全部作为直接工具" },
  { value: "proxy-only", label: "仅代理工具" },
  { value: "custom", label: "自定义工具列表" },
] as const;

const GRANT_TYPE_OPTIONS = [
  { value: "default", label: "授权码（默认）" },
  { value: "authorization_code", label: "授权码" },
  { value: "client_credentials", label: "客户端凭证" },
] as const;

// ---------------------------------------------------------------------------
// Config file access (Pi global override)
// ---------------------------------------------------------------------------

function configPath(): string {
  return getAgentPath("mcp.json");
}

function parseJsonText(raw: string): unknown {
  return JSON.parse(stripJsonComments(raw, { trailingCommas: true }));
}

function readRawConfig(): AetherJsonObject {
  const path = configPath();
  if (!existsSync(path)) return {};
  try {
    const parsed = parseJsonText(readFileSync(path, "utf8"));
    return parsed && typeof parsed === "object" && !Array.isArray(parsed) ? parsed as AetherJsonObject : {};
  } catch (error) {
    throw new Error(`无法读取 MCP 配置 ${path}：${error instanceof Error ? error.message : String(error)}`, { cause: error });
  }
}

function readServers(): Record<string, AetherJsonObject> {
  const raw = readRawConfig();
  const servers = raw.mcpServers ?? raw["mcp-servers"] ?? {};
  if (!servers || typeof servers !== "object" || Array.isArray(servers)) return {};
  const result: Record<string, AetherJsonObject> = {};
  for (const [name, entry] of Object.entries(servers)) {
    if (entry && typeof entry === "object" && !Array.isArray(entry)) result[name] = entry as AetherJsonObject;
  }
  return result;
}

function writeServers(servers: Record<string, AetherJsonObject>): void {
  const path = configPath();
  const raw = readRawConfig();
  delete raw["mcp-servers"];
  raw.mcpServers = servers;
  mkdirSync(dirname(path), { recursive: true });
  const temporaryPath = `${path}.${process.pid}.tmp`;
  writeFileSync(temporaryPath, `${JSON.stringify(raw, null, 2)}\n`, "utf8");
  try {
    chmodSync(temporaryPath, 0o600);
  } catch {
    // Windows and some container filesystems do not support POSIX modes.
  }
  renameSync(temporaryPath, path);
}

// ---------------------------------------------------------------------------
// Value normalization
// ---------------------------------------------------------------------------

function asString(value: unknown, fallback = ""): string {
  return typeof value === "string" ? value : fallback;
}

function stored(api: AetherExtensionAPI, settingId: string, fallback: string): string {
  const value = api.storage.get<string | number | boolean>(settingStorageKey(settingId))
    ?? api.storage.get<string | number | boolean>(settingId, fallback);
  return asString(value, fallback);
}

function storedBoolean(api: AetherExtensionAPI, settingId: string, fallback: boolean): boolean {
  const value = api.storage.get<boolean>(settingStorageKey(settingId))
    ?? api.storage.get<boolean>(settingId, fallback);
  return value === true;
}

function storeSetting(api: AetherExtensionAPI, settingId: string, value: string | number | boolean): void {
  api.storage.set(settingId, value);
  api.storage.set(settingStorageKey(settingId), value);
}

function clearSetting(api: AetherExtensionAPI, settingId: string): void {
  api.storage.delete(settingId);
  api.storage.delete(settingStorageKey(settingId));
}

function clearServerStorage(api: AetherExtensionAPI, serverName: string): void {
  const prefix = `server:${serverName}:`;
  for (const [key, value] of Object.entries(api.storage.snapshot())) {
    if (key.startsWith(prefix) || key.startsWith(settingStorageKey(prefix))) {
      void value;
      api.storage.delete(key);
    }
  }
  api.storage.delete(`mcp:inspect:${serverName}`);
}

function validateServerName(name: string): string {
  const trimmed = name.trim();
  if (!trimmed) throw new Error("必须填写服务器名称。");
  if (!/^[A-Za-z0-9][A-Za-z0-9._-]*$/.test(trimmed)) {
    throw new Error("服务器名称只能包含字母、数字、点、连字符和下划线，且必须以字母或数字开头。");
  }
  return trimmed;
}

function stringValue(value: string, present: boolean): string | undefined {
  return present && value.trim() !== "" ? value : undefined;
}

function integerValue(value: string, present: boolean): number | undefined {
  if (!present) return undefined;
  const trimmed = value.trim();
  if (!trimmed) return undefined;
  const parsed = Number(trimmed);
  if (!Number.isFinite(parsed) || !Number.isInteger(parsed)) throw new Error(`需要整数，实际为「${trimmed}」。`);
  return parsed;
}

function booleanValue(value: unknown): boolean | undefined {
  return value === true;
}

function parseRecord(value: string, labelText: string): Record<string, string> | undefined {
  const trimmed = value.trim();
  if (!trimmed) return undefined;
  const parsed = JSON.parse(stripJsonComments(trimmed, { trailingCommas: true }));
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) throw new Error(`${labelText} 必须是 JSON 对象。`);
  const result: Record<string, string> = {};
  for (const [key, entry] of Object.entries(parsed)) {
    if (typeof entry !== "string") throw new Error(`${labelText}.${key} 必须是字符串。`);
    result[key] = entry;
  }
  return Object.keys(result).length > 0 ? result : undefined;
}

function parseList(value: string, labelText: string): string[] | undefined {
  const trimmed = value.trim();
  if (!trimmed) return undefined;
  if (trimmed.startsWith("[")) {
    const parsed = JSON.parse(stripJsonComments(trimmed, { trailingCommas: true }));
    if (!Array.isArray(parsed) || parsed.some((entry) => typeof entry !== "string")) {
      throw new Error(`${labelText} 必须是字符串组成的 JSON 数组。`);
    }
    return parsed.length > 0 ? parsed as string[] : undefined;
  }
  const lines = trimmed.includes("\n")
    ? trimmed.split(/\r?\n/)
    : trimmed.split(",");
  const entries = lines.map((entry) => entry.trim()).filter(Boolean);
  return entries.length > 0 ? entries : undefined;
}

function parseKeywordRecord(value: string): Record<string, string[]> | undefined {
  const trimmed = value.trim();
  if (!trimmed) return undefined;
  const parsed = JSON.parse(stripJsonComments(trimmed, { trailingCommas: true }));
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) throw new Error("searchKeywords 必须是 JSON 对象。");
  const result: Record<string, string[]> = {};
  for (const [key, entry] of Object.entries(parsed)) {
    if (!Array.isArray(entry) || entry.some((keyword) => typeof keyword !== "string")) {
      throw new Error(`searchKeywords.${key} 必须是字符串数组。`);
    }
    result[key] = entry as string[];
  }
  return Object.keys(result).length > 0 ? result : undefined;
}

function formatRecord(value: unknown): string {
  if (value && typeof value === "object" && !Array.isArray(value)) return JSON.stringify(value, null, 2);
  return typeof value === "string" ? value : "";
}

function formatList(value: unknown): string {
  if (Array.isArray(value)) return value.filter((entry): entry is string => typeof entry === "string").join("\n");
  return typeof value === "string" ? value : "";
}

function deriveTransport(entry: AetherJsonObject): Transport {
  if (typeof entry.command === "string") return "stdio";
  if (typeof entry.socket === "string") return "socket";
  if (typeof entry.url === "string") return "http";
  return "stdio";
}

function serverFieldValue(entry: AetherJsonObject, field: string, fallback = ""): string {
  return asString(entry[field], fallback);
}

function authMode(entry: AetherJsonObject): string {
  if (entry.auth === "oauth") return "oauth";
  if (entry.auth === "bearer") return "bearer";
  if (entry.auth === false) return "none";
  return "auto";
}

function booleanOrListMode(value: unknown): string {
  if (value === true) return "all";
  if (value === false) return "proxy-only";
  if (Array.isArray(value)) return "custom";
  return "unset";
}

function directToolsMode(entry: AetherJsonObject): string {
  return booleanOrListMode(entry.directTools);
}

function booleanSelectValue(entry: AetherJsonObject, field: string): string {
  const value = entry[field];
  if (value === true) return "true";
  if (value === false) return "false";
  return "default";
}

function oauthValue(entry: AetherJsonObject, field: string, fallback = ""): string {
  const oauth = entry.oauth;
  if (!oauth || typeof oauth !== "object" || Array.isArray(oauth)) return fallback;
  return asString((oauth as AetherJsonObject)[field], fallback);
}

function oauthSkipIssuer(entry: AetherJsonObject): boolean {
  const oauth = entry.oauth;
  return Boolean(oauth && typeof oauth === "object" && !Array.isArray(oauth)
    ? (oauth as AetherJsonObject).skipIssuerMetadataValidation === true
    : false);
}

function requestHeadersCommandValue(entry: AetherJsonObject): string {
  return formatRecord(entry.requestHeadersCommand);
}

// ---------------------------------------------------------------------------
// Settings helpers
// ---------------------------------------------------------------------------

function text(id: string, labelText: string, description: string, value: string, extra: Partial<AetherSettingDefinition> = {}): AetherSettingDefinition {
  return { id, type: "text", label: labelText, description, default: value, ...extra };
}

function password(id: string, labelText: string, description: string, value: string): AetherSettingDefinition {
  return { id, type: "password", label: labelText, description, default: value };
}

function textarea(id: string, labelText: string, description: string, value: string): AetherSettingDefinition {
  return { id, type: "textarea", label: labelText, description, default: value };
}

function number(id: string, labelText: string, description: string, value: string): AetherSettingDefinition {
  return { id, type: "number", label: labelText, description, default: value, min: 0 };
}

function toggle(id: string, labelText: string, description: string, value: boolean): AetherSettingDefinition {
  return { id, type: "toggle", label: labelText, description, default: value };
}

function select(id: string, labelText: string, description: string, value: string, options: ReadonlyArray<{ value: string; label: string }>): AetherSettingDefinition {
  return { id, type: "select", label: labelText, description, default: value, options: options.map((option) => ({ value: option.value, label: option.label })) };
}

function button(id: string, labelText: string, action: string, args: AetherJsonObject, description = "", tone: "primary" | "neutral" | "danger" = "neutral"): AetherSettingDefinition {
  return { id, type: "button", label: labelText, description, action, args, tone };
}

function label(id: string, labelText: string, description: string): AetherSettingDefinition {
  return { id, type: "label", label: labelText, description };
}

function link(id: string, labelText: string, url: string, description = ""): AetherSettingDefinition {
  return { id, type: "link", label: labelText, url, description };
}

// ---------------------------------------------------------------------------
// Form builders
// ---------------------------------------------------------------------------

function transportSettings(api: AetherExtensionAPI, prefix: string, transport: Transport, entry: AetherJsonObject): AetherSettingDefinition[] {
  const value = (id: string, fallback: string) => stored(api, id, fallback);
  const settings: AetherSettingDefinition[] = [];
  if (transport === "stdio") {
    settings.push(
      text(`${prefix}command`, "命令", "stdio 传输要执行的程序，例如 npx 或 uvx。", value(`${prefix}command`, serverFieldValue(entry, "command"))),
      textarea(`${prefix}args`, "参数", "每行一个参数，支持环境变量插值。", value(`${prefix}args`, formatList(entry.args))),
      textarea(`${prefix}env`, "环境变量", 'JSON 对象，例如 {"API_KEY": "$ENV_VAR"}。以 ! 开头的值会在服务器连接时运行命令。', value(`${prefix}env`, formatRecord(entry.env))),
      text(`${prefix}cwd`, "工作目录", "可选。支持 ${VAR}、$env:VAR 和 ~。", value(`${prefix}cwd`, serverFieldValue(entry, "cwd"))),
    );
  } else if (transport === "http") {
    settings.push(
      text(`${prefix}url`, "URL", "HTTP MCP 端点。支持 Streamable HTTP 和旧版 SSE。", value(`${prefix}url`, serverFieldValue(entry, "url"))),
      select(`${prefix}httpTransport`, "HTTP 传输", "强制指定传输方式，或由适配器自动协商。", value(`${prefix}httpTransport`, asString(entry.httpTransport, "auto")), HTTP_TRANSPORT_OPTIONS),
      select(`${prefix}auth`, "身份验证", "默认自动检测 OAuth，除非配置了自定义请求头。", value(`${prefix}auth`, authMode(entry)), AUTH_OPTIONS),
      password(`${prefix}bearerToken`, "Bearer Token", "可选的静态令牌。支持 ${VAR}、$env:VAR 和 !command 来源。", value(`${prefix}bearerToken`, serverFieldValue(entry, "bearerToken"))),
      text(`${prefix}bearerTokenEnv`, "Bearer Token 环境变量", "存放 Bearer Token 的可选环境变量。", value(`${prefix}bearerTokenEnv`, serverFieldValue(entry, "bearerTokenEnv"))),
      textarea(`${prefix}headers`, "HTTP 请求头", 'JSON 对象，例如 {"Authorization": "Bearer ${TOKEN}"}。', value(`${prefix}headers`, formatRecord(entry.headers))),
      select(`${prefix}oauthGrantType`, "OAuth 授权类型", "客户端凭证模式无需打开浏览器即可完成。", value(`${prefix}oauthGrantType`, oauthValue(entry, "grantType", "default")), GRANT_TYPE_OPTIONS),
      text(`${prefix}oauthClientId`, "OAuth 客户端 ID", "可选的预注册客户端 ID。留空则使用动态注册。", value(`${prefix}oauthClientId`, oauthValue(entry, "clientId"))),
      password(`${prefix}oauthClientSecret`, "OAuth 客户端密钥", "可选的机密客户端密钥。以 ! 开头会运行命令。", value(`${prefix}oauthClientSecret`, oauthValue(entry, "clientSecret"))),
      text(`${prefix}oauthScope`, "OAuth 范围", "向授权服务器请求的范围，用空格分隔。", value(`${prefix}oauthScope`, oauthValue(entry, "scope"))),
      text(`${prefix}oauthRedirectUri`, "OAuth 重定向 URI", "预注册的 localhost 回调地址，须包含端口和路径。", value(`${prefix}oauthRedirectUri`, oauthValue(entry, "redirectUri"))),
      text(`${prefix}oauthClientName`, "OAuth 客户端名称", "动态注册时展示的客户端名称。", value(`${prefix}oauthClientName`, oauthValue(entry, "clientName"))),
      text(`${prefix}oauthClientUri`, "OAuth 客户端 URI", "动态注册时展示的客户端主页。", value(`${prefix}oauthClientUri`, oauthValue(entry, "clientUri"))),
      text(`${prefix}oauthLogoUri`, "OAuth Logo URL", "动态注册时展示的绝对 http(s) Logo 地址。", value(`${prefix}oauthLogoUri`, oauthValue(entry, "logoUri"))),
      toggle(`${prefix}oauthSkipIssuerValidation`, "跳过 OAuth 签发方校验", "仅用于已知配置有误的授权服务器，会降低安全性。", oauthSkipIssuer(entry)),
      textarea(`${prefix}oauthAuthorizationParams`, "OAuth 授权参数", "额外授权 URL 参数的可选 JSON 对象。", value(`${prefix}oauthAuthorizationParams`, formatRecord((entry.oauth as AetherJsonObject | undefined)?.authorizationParams))),
      textarea(`${prefix}requestHeadersCommand`, "每次请求的请求头命令", '可选 JSON 对象 { "command": "...", "args": [...] }。为每次 HTTP 请求生成失败即关闭的请求头。', value(`${prefix}requestHeadersCommand`, requestHeadersCommandValue(entry))),
    );
  } else {
    settings.push(
      text(`${prefix}socket`, "套接字路径", "明确的 rmcp-mux Unix 域套接字。支持 ${VAR}、$env:VAR 和 ~。", value(`${prefix}socket`, serverFieldValue(entry, "socket"))),
    );
  }
  return settings;
}

function commonSettings(api: AetherExtensionAPI, prefix: string, entry: AetherJsonObject): AetherSettingDefinition[] {
  const value = (id: string, fallback: string) => stored(api, id, fallback);
  const directMode = value(`${prefix}directTools`, directToolsMode(entry));
  return [
    select(`${prefix}lifecycle`, "生命周期", "何时连接服务器进程或 HTTP 会话。", value(`${prefix}lifecycle`, asString(entry.lifecycle, "lazy")), LIFECYCLE_OPTIONS),
    number(`${prefix}idleTimeout`, "空闲超时（分钟）", "空闲多久后断开。留空使用全局设置，0 表示禁用空闲超时。", value(`${prefix}idleTimeout`, entry.idleTimeout === undefined ? "" : String(entry.idleTimeout))),
    number(`${prefix}requestTimeoutMs`, "请求超时（毫秒）", "实时请求超时毫秒数。留空或 0 使用 SDK 默认值。", value(`${prefix}requestTimeoutMs`, entry.requestTimeoutMs === undefined ? "" : String(entry.requestTimeoutMs))),
    select(`${prefix}protocolVersion`, "MCP 协议版本", "默认使用旧版。自动会尝试 2026-07-28，失败则回退旧版。", value(`${prefix}protocolVersion`, asString(entry.protocolVersion, "legacy")), PROTOCOL_OPTIONS),
    select(`${prefix}exposeResources`, "公开资源", "将 MCP 资源公开为可调用工具。", value(`${prefix}exposeResources`, booleanSelectValue(entry, "exposeResources")), BOOLEAN_OPTIONS),
    select(`${prefix}directTools`, "直接工具", "单独注册工具，而不是经过 mcp 代理工具。", directMode, DIRECT_TOOLS_OPTIONS),
    textarea(`${prefix}directToolsList`, "自定义直接工具", "工具名称，每行一个或 JSON 数组。仅在上方选择「自定义」时使用。", value(`${prefix}directToolsList`, formatList(entry.directTools))),
    select(`${prefix}toolPrefix`, "工具前缀", "此服务器公开工具时的前缀风格。", value(`${prefix}toolPrefix`, asString(entry.toolPrefix, "unset")), TOOL_PREFIX_OPTIONS),
    textarea(`${prefix}includeTools`, "包含工具", "可选的工具名称或 glob 模式。留空表示包含全部工具。", value(`${prefix}includeTools`, formatList(entry.includeTools))),
    textarea(`${prefix}excludeTools`, "排除工具", "可选的工具名称或 glob 模式，在包含列表之后再隐藏。", value(`${prefix}excludeTools`, formatList(entry.excludeTools))),
    textarea(`${prefix}searchKeywords`, "搜索关键词", '可选 JSON 对象，例如 {"list_issues": ["github", "issues"]}。', value(`${prefix}searchKeywords`, formatRecord(entry.searchKeywords))),
    select(`${prefix}approveTools`, "需要批准", "调用匹配的工具前需要交互式批准。", value(`${prefix}approveTools`, booleanOrListMode(entry.approveTools)), DIRECT_TOOLS_OPTIONS),
    textarea(`${prefix}approveToolsList`, "自定义批准工具", "工具名称，每行一个或 JSON 数组。仅在上方选择「自定义」时使用。", value(`${prefix}approveToolsList`, formatList(entry.approveTools))),
    toggle(`${prefix}debug`, "显示 stderr", "在 Pi 会话日志中显示服务器的 stderr。", entry.debug === true),
    toggle(`${prefix}trace`, "协议跟踪", "为此服务器启用仅元数据的 JSONL 协议跟踪。", entry.trace === true),
    toggle(`${prefix}disabled`, "已停用", "保留此服务器配置，但禁止连接和工具调用。", entry.disabled === true),
  ];
}

function serverCategory(api: AetherExtensionAPI, name: string, entry: AetherJsonObject, index: number, snapshot?: McpAetherSnapshot): AetherSettingsCategory {
  const selectedTransport = stored(api, `server:${name}:transport`, deriveTransport(entry));
  const transport: Transport = selectedTransport === "http" ? "http" : selectedTransport === "socket" ? "socket" : "stdio";
  const runtime = snapshot?.servers.find((server) => server.name === name);
  const status = runtime?.status ?? "not-connected";
  const toolText = runtime ? `${runtime.toolCount} 个工具` : "尚未初始化";
  const subtitle = `${transportLabel(transport)} · ${statusLabel(status)} · ${toolText}`;
  const value = (id: string, fallback: string) => stored(api, id, fallback);
  const settings: AetherSettingDefinition[] = [
    label(`server:${name}:runtime`, status === "failed" && runtime?.failedAgoSeconds !== undefined
      ? `状态：${statusLabel(status)}，${runtime.failedAgoSeconds} 秒前`
      : `状态：${statusLabel(status)}`, `Pi 插件报告的运行状态。${runtime?.disabled ? "此服务器已停用。" : ""}`),
    select(`server:${name}:transport`, "传输方式", "切换传输方式会清除原先传输专用的字段。", value(`server:${name}:transport`, transport), TRANSPORT_OPTIONS),
    ...transportSettings(api, `server:${name}:`, transport, entry),
    ...commonSettings(api, `server:${name}:`, entry),
  ];
  const sections: AetherSettingsSection[] = [
    { id: "configuration", title: "配置", settings },
    {
      id: "actions",
      title: "管理",
      settings: [
        text(`server:${name}:renameTo`, "重命名为", "输入新名称，然后点「重命名服务器」。", value(`server:${name}:renameTo`, "")),
        button(`server:${name}:rename`, "重命名服务器", "mcp:rename-server", { serverName: name }, "重命名此服务器并保留其配置。", "neutral"),
        button(`server:${name}:reconnect`, "重新连接", "mcp:reconnect-server", { serverName: name }, "关闭并重新连接此服务器，无需重新加载 Pi。", "primary"),
        ...(transport === "http" && entry.auth !== false
          ? [
              button(`server:${name}:auth-start`, "进行 OAuth 授权", "mcp:auth-start", { serverName: name }, "为此服务器开始或继续 OAuth 流程。", "primary"),
              button(`server:${name}:logout-oauth`, "清除 OAuth 凭据", "mcp:oauth-logout", { serverName: name }, "删除已保存的 OAuth 凭据并关闭连接。", "neutral"),
            ]
          : []),
        button(`server:${name}:remove`, "删除服务器", "mcp:remove-server", { serverName: name }, "从此 Pi MCP 配置中删除此服务器。", "danger"),
      ],
    },
  ];
  return {
    id: `server:${name}`,
    title: name,
    subtitle,
    icon: "auto",
    order: 20 + index,
    trailingIcon: "delete",
    trailingAction: "mcp:remove-server",
    trailingArgs: { serverName: name },
    sections,
  };
}

function newServerSection(api: AetherExtensionAPI): AetherSettingsSection {
  const value = (id: string, fallback: string) => stored(api, id, fallback);
  const transport = (value("new_transport", "stdio") === "http" ? "http" : value("new_transport", "stdio") === "socket" ? "socket" : "stdio") as Transport;
  const empty: AetherJsonObject = {};
  return {
    id: "new-server",
    title: "新建 MCP 服务器",
    settings: [
      text("new_name", "名称", "唯一服务器名称，用作配置键和默认工具前缀。", value("new_name", "")),
      select("new_transport", "传输方式", "MCP 适配器支持的全部传输方式。", transport, TRANSPORT_OPTIONS),
      ...transportSettings(api, "new_", transport, empty),
      ...commonSettings(api, "new_", empty),
      button("add-server", "添加 MCP 服务器", "mcp:add-server", {}, "将此服务器写入 Pi MCP 配置，然后重新加载以连接。", "primary"),
    ],
  };
}

function buildMainSections(api: AetherExtensionAPI, servers: Record<string, AetherJsonObject>, snapshot: McpAetherSnapshot): AetherSettingsSection[] {
  const serverNames = Object.keys(servers).sort();
  const serverCount = serverNames.length;
  const sections: AetherSettingsSection[] = [];
  const readyText = snapshot.ready ? "已就绪" : "尚未初始化";

  if (serverCount === 0) {
    sections.push({
      id: "empty-state-section",
      settings: [
        {
          id: "no-servers-state",
          type: "empty-state",
          title: "还没有 MCP 服务器",
          description: "添加 HTTP 或 stdio 服务器以扩展能力。",
          buttonLabel: "添加服务器",
          category: "new-server",
        },
      ],
    });
  } else {
    // Runtime status summary at the top
    sections.push({
      id: "runtime",
      title: "MCP 运行时",
      description: `Pi MCP 桥接${readyText}。配置：${snapshot.configPath}`,
      settings: [
        label("runtime-summary", `已配置 ${serverCount} 个服务器 · ${snapshot.connectedCount} 个已连接 · ${snapshot.totalTools} 个工具`, `Pi MCP 桥接${readyText}。配置文件：${snapshot.configPath}`),
        {
          id: "runtime-actions",
          type: "action-row",
          actions: [
            { label: `重新加载（${serverCount} 个服务器）`, action: "mcp:reload" },
            { label: `全部重新连接（${snapshot.connectedCount} 个活动）`, action: "mcp:reconnect-all" },
          ],
        },
      ],
    });

    // Server Cards section
    const serverCards: AetherSettingDefinition[] = serverNames.map((name) => {
      const entry = servers[name] as AetherJsonObject;
      const transport = deriveTransport(entry);
      const runtime = snapshot.servers.find((s) => s.name === name);
      const transportBadge = transport === "http" ? "STREAMABLE_HTTP" : transport === "socket" ? "UNIX_SOCKET" : "STDIO";
      const statusText = runtime?.status === "connected"
        ? "● 已连接"
        : runtime?.status === "failed"
        ? "▲ 失败"
        : runtime?.status === "needs-auth"
        ? "🔑 需要授权"
        : "○ 未连接";
      const pillText = `${statusText} · ${runtime ? `${runtime.toolCount} 个工具` : "0 个工具"}`;
      const inspectOutput = asString(api.storage.get(`mcp:inspect:${name}`), "");

      const details: Array<{ label: string; value: string }> = [
        { label: "服务器 ID", value: name },
        { label: "传输方式", value: transportBadge },
      ];
      if (transport === "http") {
        details.push({ label: "URL", value: serverFieldValue(entry, "url") });
        details.push({ label: "请求头", value: String(Object.keys(entry.headers || {}).length) });
        if (entry.auth) details.push({ label: "身份验证", value: authMode(entry) });
      } else if (transport === "stdio") {
        details.push({ label: "命令", value: serverFieldValue(entry, "command") });
        if (Array.isArray(entry.args) && entry.args.length > 0) {
          details.push({ label: "参数", value: entry.args.join(" ") });
        }
        if (entry.cwd) details.push({ label: "工作目录", value: String(entry.cwd) });
        if (entry.env && typeof entry.env === "object") {
          details.push({ label: "环境变量", value: String(Object.keys(entry.env).length) });
        }
      } else {
        details.push({ label: "套接字路径", value: serverFieldValue(entry, "socket") });
      }
      details.push({ label: "生命周期", value: asString(entry.lifecycle, "lazy") });
      details.push({
        label: "请求超时",
        value: entry.requestTimeoutMs !== undefined ? `${entry.requestTimeoutMs} ms` : "SDK 默认值",
      });

      const actions: Array<{ label: string; action: string; args?: AetherJsonObject; tone?: "primary" | "neutral" | "danger" }> = [
        { label: "工具", action: "mcp:inspect-tools", args: { serverName: name } },
        { label: "资源", action: "mcp:inspect-resources", args: { serverName: name } },
        { label: "提示词", action: "mcp:inspect-prompts", args: { serverName: name } },
        { label: "重新连接", action: "mcp:reconnect-server", args: { serverName: name } },
      ];
      if (transport === "http" && entry.auth !== false) {
        actions.push({ label: "OAuth", action: "mcp:auth-start", args: { serverName: name } });
      }

      return {
        id: `card_${name}`,
        type: "item-card",
        title: name,
        subtitle: transportBadge,
        pill: pillText,
        checked: entry.disabled !== true,
        toggleAction: "mcp:toggle-server",
        editCategory: `server:${name}`,
        deleteAction: "mcp:remove-server",
        deleteArgs: { serverName: name },
        actions,
        details,
        ...(inspectOutput ? { resultText: inspectOutput } : {}),
      };
    });

    sections.push({
      id: "servers",
      title: "已配置的服务器",
      description: "点按任意服务器可查看详情、检查可用工具或重新连接。",
      settings: serverCards,
    });
  }

  // Pending OAuth section if any
  const pendingOAuth = oauthSection(api);
  if (pendingOAuth) sections.push(pendingOAuth);

  return sections;
}

function oauthSection(api: AetherExtensionAPI): AetherSettingsSection | undefined {
  const pending = api.storage.get<AetherJsonObject>(BRIDGE_OAUTH_KEY);
  if (!pending || typeof pending.serverName !== "string") return undefined;
  const authorizationUrl = asString(pending.authorizationUrl);
  if (!authorizationUrl) return undefined;
  const value = stored(api, "oauth-input", "");
  return {
    id: "oauth",
    title: "MCP OAuth",
    settings: [
      label("oauth-summary", `授权 ${pending.serverName}`, "打开授权网址，批准访问，然后将完整的回调 URL 或授权码粘贴到这里。"),
      link("oauth-url", "打开授权网址", authorizationUrl, "浏览器可能无法从另一台设备访问 localhost 回调；请将重定向 URL 手动粘贴到下方。"),
      textarea("oauth-input", "回调 URL 或授权码", "批准访问后，从浏览器地址栏粘贴完整 URL。", value),
      button("oauth-complete", "完成 OAuth", "mcp:oauth-complete", { serverName: pending.serverName }, "交换授权码并重新连接服务器。", "primary"),
    ],
  };
}

function transportLabel(transport: Transport): string {
  if (transport === "stdio") return "stdio";
  if (transport === "socket") return "Unix 套接字";
  return "http";
}

function statusLabel(status: McpAetherServerSnapshot["status"]): string {
  switch (status) {
    case "connected":
      return "已连接";
    case "cached":
      return "已缓存";
    case "failed":
      return "失败";
    case "needs-auth":
      return "需要授权";
    case "not-connected":
      return "未连接";
    case "disabled":
      return "已停用";
    default:
      return status.replaceAll("-", " ");
  }
}

function emptySnapshot(): McpAetherSnapshot {
  return {
    ready: false,
    configPath: configPath(),
    servers: [],
    totalTools: 0,
    totalResources: 0,
    connectedCount: 0,
    disabledCount: 0,
  };
}

// ---------------------------------------------------------------------------
// Server mutation helpers
// ---------------------------------------------------------------------------

function parseOAuthObject(api: AetherExtensionAPI, prefix: string, existing: AetherJsonObject, force: boolean): AetherJsonObject | undefined {
  const value = (id: string) => stored(api, id, "");
  const clientId = stringValue(value(`${prefix}oauthClientId`), true);
  const clientSecret = stringValue(value(`${prefix}oauthClientSecret`), true);
  const scope = stringValue(value(`${prefix}oauthScope`), true);
  const redirectUri = stringValue(value(`${prefix}oauthRedirectUri`), true);
  const clientName = stringValue(value(`${prefix}oauthClientName`), true);
  const clientUri = stringValue(value(`${prefix}oauthClientUri`), true);
  const logoUri = stringValue(value(`${prefix}oauthLogoUri`), true);
  const grantType = value(`${prefix}oauthGrantType`);
  const skipIssuer = storedBoolean(api, `${prefix}oauthSkipIssuerValidation`, false);
  const authorizationParams = parseRecord(value(`${prefix}oauthAuthorizationParams`), "OAuth 授权参数");
  const previous = existing.oauth && typeof existing.oauth === "object" && !Array.isArray(existing.oauth)
    ? existing.oauth as AetherJsonObject
    : {};
  const next: AetherJsonObject = { ...previous };
  if (grantType !== "default" && grantType !== "") next.grantType = grantType;
  else delete next.grantType;
  if (clientId !== undefined) next.clientId = clientId; else delete next.clientId;
  if (clientSecret !== undefined) next.clientSecret = clientSecret; else delete next.clientSecret;
  if (scope !== undefined) next.scope = scope; else delete next.scope;
  if (redirectUri !== undefined) next.redirectUri = redirectUri; else delete next.redirectUri;
  if (clientName !== undefined) next.clientName = clientName; else delete next.clientName;
  if (clientUri !== undefined) next.clientUri = clientUri; else delete next.clientUri;
  if (logoUri !== undefined) next.logoUri = logoUri; else delete next.logoUri;
  if (skipIssuer) next.skipIssuerMetadataValidation = true; else delete next.skipIssuerMetadataValidation;
  if (authorizationParams !== undefined) next.authorizationParams = authorizationParams; else delete next.authorizationParams;
  return (force || Object.keys(next).length > 0) ? next : undefined;
}

function applyText(entry: AetherJsonObject, field: string, raw: unknown): void {
  const value = asString(raw);
  const next = stringValue(value, true);
  if (next !== undefined) entry[field] = next;
  else delete entry[field];
}

function applyInteger(entry: AetherJsonObject, field: string, raw: unknown): void {
  const value = integerValue(asString(raw), true);
  if (value !== undefined) entry[field] = value;
  else delete entry[field];
}

function applyList(entry: AetherJsonObject, field: string, raw: unknown, labelText: string): void {
  const value = parseList(asString(raw), labelText);
  if (value !== undefined) entry[field] = value;
  else delete entry[field];
}

function applyRecord(entry: AetherJsonObject, field: string, raw: unknown, labelText: string): void {
  const value = parseRecord(asString(raw), labelText);
  if (value !== undefined) entry[field] = value;
  else delete entry[field];
}

function applyServerField(api: AetherExtensionAPI, serverName: string, field: string, raw: unknown): boolean {
  const servers = readServers();
  const entry = servers[serverName];
  if (!entry) throw new Error(`服务器「${serverName}」已不存在。`);
  const next: AetherJsonObject = { ...entry };
  const value = asString(raw);
  const prefix = `server:${serverName}:`;

  switch (field) {
    case "transport":
      for (const key of ["command", "args", "env", "cwd", "url", "headers", "requestHeadersCommand", "auth", "bearerToken", "bearerTokenEnv", "oauth", "socket"]) delete next[key];
      break;
    case "command":
    case "url":
    case "socket":
    case "cwd":
    case "bearerToken":
    case "bearerTokenEnv":
      applyText(next, field, raw);
      break;
    case "args":
      applyList(next, "args", raw, "参数");
      break;
    case "env":
    case "headers":
      applyRecord(next, field, raw, field === "env" ? "环境变量" : "HTTP 请求头");
      break;
    case "httpTransport": {
      const selected = value === "streamable-http" || value === "sse" ? value : "auto";
      if (selected === "auto") delete next.httpTransport;
      else next.httpTransport = selected;
      break;
    }
    case "auth": {
      if (value === "oauth") {
        next.auth = "oauth";
        delete next.bearerToken;
        delete next.bearerTokenEnv;
        next.oauth = parseOAuthObject(api, prefix, next, true) ?? {};
      } else if (value === "bearer") {
        next.auth = "bearer";
        delete next.bearerTokenEnv;
        delete next.oauth;
      } else if (value === "none") {
        next.auth = false;
        delete next.bearerToken;
        delete next.bearerTokenEnv;
        delete next.oauth;
      } else {
        delete next.auth;
        delete next.bearerToken;
        delete next.bearerTokenEnv;
      }
      break;
    }
    case "oauthGrantType":
    case "oauthClientId":
    case "oauthClientSecret":
    case "oauthScope":
    case "oauthRedirectUri":
    case "oauthClientName":
    case "oauthClientUri":
    case "oauthLogoUri":
    case "oauthSkipIssuerValidation":
    case "oauthAuthorizationParams":
      next.oauth = parseOAuthObject(api, prefix, next, false);
      break;
    case "requestHeadersCommand": {
      const trimmed = value.trim();
      if (!trimmed) delete next.requestHeadersCommand;
      else {
        const parsed = JSON.parse(stripJsonComments(trimmed, { trailingCommas: true }));
        if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) throw new Error("requestHeadersCommand 必须是 JSON 对象。");
        next.requestHeadersCommand = parsed;
      }
      break;
    }
    case "lifecycle": {
      if (value === "lazy" || value === "eager" || value === "keep-alive" || value === "lazy-keep-alive") next.lifecycle = value;
      else delete next.lifecycle;
      break;
    }
    case "idleTimeout":
      applyInteger(next, "idleTimeout", raw);
      break;
    case "requestTimeoutMs":
      applyInteger(next, "requestTimeoutMs", raw);
      break;
    case "protocolVersion": {
      if (value === "auto" || value === "2026-07-28") next.protocolVersion = value;
      else if (value === "legacy") next.protocolVersion = "legacy";
      else delete next.protocolVersion;
      break;
    }
    case "exposeResources": {
      if (value === "true") next.exposeResources = true;
      else if (value === "false") next.exposeResources = false;
      else delete next.exposeResources;
      break;
    }
    case "directTools": {
      if (value === "all") next.directTools = true;
      else if (value === "proxy-only") next.directTools = false;
      else if (value === "custom") {
        const list = parseList(stored(api, `${prefix}directToolsList`, ""), "自定义直接工具");
        if (list !== undefined) next.directTools = list;
        else delete next.directTools;
      } else delete next.directTools;
      break;
    }
    case "toolPrefix": {
      if (value === "server" || value === "short" || value === "none" || value === "mcp") next.toolPrefix = value;
      else delete next.toolPrefix;
      break;
    }
    case "includeTools":
      applyList(next, "includeTools", raw, "包含工具");
      break;
    case "excludeTools":
      applyList(next, "excludeTools", raw, "排除工具");
      break;
    case "searchKeywords": {
      const parsed = parseKeywordRecord(value);
      if (parsed !== undefined) next.searchKeywords = parsed;
      else delete next.searchKeywords;
      break;
    }
    case "approveTools": {
      if (value === "all") next.approveTools = true;
      else if (value === "proxy-only") next.approveTools = false;
      else if (value === "custom") {
        const list = parseList(stored(api, `${prefix}approveToolsList`, ""), "自定义批准工具");
        if (list !== undefined) next.approveTools = list;
        else delete next.approveTools;
      } else delete next.approveTools;
      break;
    }
    case "debug":
    case "trace":
    case "disabled":
      if (booleanValue(raw)) next[field] = true;
      else delete next[field];
      break;
    default:
      return false;
  }

  writeServers({ ...servers, [serverName]: next });
  return true;
}

function buildEntryFromForm(api: AetherExtensionAPI, name: string, transport: Transport): AetherJsonObject {
  const entry: AetherJsonObject = {};
  const value = (id: string) => stored(api, id, "");
  if (transport === "stdio") {
    const command = value("new_command").trim();
    if (!command) throw new Error("stdio 服务器必须填写命令。");
    entry.command = command;
    const args = parseList(value("new_args"), "参数");
    if (args !== undefined) entry.args = args;
    const env = parseRecord(value("new_env"), "环境变量");
    if (env !== undefined) entry.env = env;
    const cwd = stringValue(value("new_cwd"), true);
    if (cwd !== undefined) entry.cwd = cwd;
  } else if (transport === "http") {
    const url = value("new_url").trim();
    if (!url) throw new Error("HTTP MCP 服务器必须填写 URL。");
    entry.url = url;
    const httpTransport = value("new_httpTransport");
    if (httpTransport === "streamable-http" || httpTransport === "sse") entry.httpTransport = httpTransport;
    const auth = value("new_auth");
    if (auth === "oauth") {
      entry.auth = "oauth";
      entry.oauth = parseOAuthObject(api, "new_", entry, true) ?? {};
    } else if (auth === "bearer") {
      entry.auth = "bearer";
    } else if (auth === "none") {
      entry.auth = false;
    } else {
      const oauth = parseOAuthObject(api, "new_", entry, false);
      if (oauth !== undefined) entry.oauth = oauth;
    }
    const bearerToken = stringValue(value("new_bearerToken"), true);
    if (bearerToken !== undefined) entry.bearerToken = bearerToken;
    const bearerTokenEnv = stringValue(value("new_bearerTokenEnv"), true);
    if (bearerTokenEnv !== undefined) entry.bearerTokenEnv = bearerTokenEnv;
    const headers = parseRecord(value("new_headers"), "HTTP 请求头");
    if (headers !== undefined) entry.headers = headers;
    const requestHeadersCommand = stringValue(value("new_requestHeadersCommand"), true);
    if (requestHeadersCommand !== undefined) {
      const parsed = JSON.parse(stripJsonComments(requestHeadersCommand, { trailingCommas: true }));
      if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) throw new Error("requestHeadersCommand 必须是 JSON 对象。");
      entry.requestHeadersCommand = parsed;
    }
  } else {
    const socket = value("new_socket").trim();
    if (!socket) throw new Error("Unix 套接字服务器必须填写套接字路径。");
    entry.socket = socket;
  }

  const lifecycle = value("new_lifecycle");
  if (lifecycle === "eager" || lifecycle === "keep-alive" || lifecycle === "lazy-keep-alive" || lifecycle === "lazy") entry.lifecycle = lifecycle;
  const idleTimeout = integerValue(value("new_idleTimeout"), true);
  if (idleTimeout !== undefined) entry.idleTimeout = idleTimeout;
  const requestTimeoutMs = integerValue(value("new_requestTimeoutMs"), true);
  if (requestTimeoutMs !== undefined) entry.requestTimeoutMs = requestTimeoutMs;
  const protocolVersion = value("new_protocolVersion");
  if (protocolVersion === "auto" || protocolVersion === "2026-07-28" || protocolVersion === "legacy") entry.protocolVersion = protocolVersion;
  const exposeResources = value("new_exposeResources");
  if (exposeResources === "true") entry.exposeResources = true;
  if (exposeResources === "false") entry.exposeResources = false;
  const directTools = value("new_directTools");
  if (directTools === "all") entry.directTools = true;
  if (directTools === "proxy-only") entry.directTools = false;
  if (directTools === "custom") {
    const list = parseList(value("new_directToolsList"), "自定义直接工具");
    if (list !== undefined) entry.directTools = list;
  }
  const toolPrefix = value("new_toolPrefix");
  if (toolPrefix === "server" || toolPrefix === "short" || toolPrefix === "none" || toolPrefix === "mcp") entry.toolPrefix = toolPrefix;
  const includeTools = parseList(value("new_includeTools"), "包含工具");
  if (includeTools !== undefined) entry.includeTools = includeTools;
  const excludeTools = parseList(value("new_excludeTools"), "排除工具");
  if (excludeTools !== undefined) entry.excludeTools = excludeTools;
  const searchKeywords = parseKeywordRecord(value("new_searchKeywords"));
  if (searchKeywords !== undefined) entry.searchKeywords = searchKeywords;
  const approveTools = value("new_approveTools");
  if (approveTools === "all") entry.approveTools = true;
  if (approveTools === "proxy-only") entry.approveTools = false;
  if (approveTools === "custom") {
    const list = parseList(value("new_approveToolsList"), "自定义批准工具");
    if (list !== undefined) entry.approveTools = list;
  }
  if (storedBoolean(api, "new_debug", false)) entry.debug = true;
  if (storedBoolean(api, "new_trace", false)) entry.trace = true;
  if (storedBoolean(api, "new_disabled", false)) entry.disabled = true;
  return entry;
}

function clearNewServerForm(api: AetherExtensionAPI): void {
  for (const key of Object.keys(api.storage.snapshot())) {
    if (key.startsWith("new_") || key.startsWith(settingStorageKey("new_"))) api.storage.delete(key);
  }
}

// ---------------------------------------------------------------------------
// Aether Script Mod entrypoint
// ---------------------------------------------------------------------------

const MCP_TOOL_TITLES = [
  ["mcp", "正在调用 MCP", "已调用 MCP"],
  ["mcpScript", "正在运行 MCP 脚本", "已运行 MCP 脚本"],
] as const;

export const activateAether = async (aether: AetherExtensionAPI) => {
  for (const [toolName, runningTitle, completedTitle] of MCP_TOOL_TITLES) {
    aether.registerToolTitle?.(toolName, runningTitle, completedTitle, 200);
  }
  const staticToolNames = new Set<string>(MCP_TOOL_TITLES.map(([toolName]) => toolName));
  const dynamicToolTitleCleanups = new Map<string, () => void>();
  const syncDynamicToolTitles = () => {
    const toolNames = readMcpAetherBridge()?.getSnapshot().toolNames ?? [];
    const current = new Set(toolNames);
    for (const [toolName, cleanup] of dynamicToolTitleCleanups) {
      if (current.has(toolName)) continue;
      cleanup();
      dynamicToolTitleCleanups.delete(toolName);
    }
    for (const toolName of current) {
      if (staticToolNames.has(toolName) || dynamicToolTitleCleanups.has(toolName)) continue;
      const cleanup = aether.registerToolTitle?.(toolName, `正在调用 ${toolName}`, `已调用 ${toolName}`, 100);
      if (typeof cleanup === "function") dynamicToolTitleCleanups.set(toolName, cleanup);
    }
  };
  syncDynamicToolTitles();
  let unregisterSettings: (() => void) | undefined;
  let refreshTimer: ReturnType<typeof setTimeout> | undefined;
  let lastFingerprint = "";
  let seeded = false;


// ---------------------------------------------------------------------------
  const pageFingerprint = (servers: Record<string, AetherJsonObject>, snapshot: McpAetherSnapshot, pendingOAuth: boolean): string => JSON.stringify({
    path: snapshot.configPath,
    ready: snapshot.ready,
    pendingOAuth,
    servers: Object.keys(servers).sort().map((name) => [name, deriveTransport(servers[name] as AetherJsonObject)]),
    statuses: snapshot.servers.map((server) => [server.name, server.status, server.toolCount, server.resourceCount ?? null, server.failedAgoSeconds ?? null, server.disabled]),
    totals: [snapshot.totalTools, snapshot.totalResources, snapshot.connectedCount, snapshot.disabledCount],
    inspections: Object.keys(servers).map((name) => [name, asString(aether.storage.get(`mcp:inspect:${name}`), "")]),
  });

  const registerSettingActions = (definition: { sections?: AetherSettingsSection[]; categories?: AetherSettingsCategory[] }) => {
    const sections = [...(definition.sections ?? []), ...(definition.categories ?? []).flatMap((category) => category.sections)];
    const seen = new Set<string>();
    for (const section of sections) {
      for (const setting of section.settings) {
        const type = setting.type ?? "text";
        if (type === "button" || type === "link" || type === "label" || type === "divider" || type === "spacer" || type === "item-card" || type === "empty-state" || type === "action-row") continue;
        if (seen.has(setting.id)) continue;
        seen.add(setting.id);
        aether.registerAction(`settings:${PAGE_ID}:${setting.id}`, async (payload) => {
          const raw = payload.value !== undefined ? payload.value : payload.checked;
          const current: string | number | boolean =
            typeof raw === "string" || typeof raw === "number" || typeof raw === "boolean" ? raw : "";
          if (setting.id.startsWith("new_")) {
            storeSetting(aether, setting.id, current);
            if (setting.id === "new_transport") scheduleRefresh(true);
            return { setting: setting.id, value: current };
          }
          if (setting.id.startsWith("server:")) {
            const separator = setting.id.indexOf(":", "server:".length);
            if (separator === -1) return { setting: setting.id, value: current };
            const serverName = setting.id.slice("server:".length, separator);
            const field = setting.id.slice(separator + 1);
            storeSetting(aether, setting.id, current);
            try {
              if (field === "renameTo") return { setting: setting.id, value: current };
              const changed = applyServerField(aether, serverName, field, current);
              if (!changed) return { setting: setting.id, value: current };
              if (field === "transport") scheduleRefresh(true);
              return { setting: setting.id, value: current };
            } catch (error) {
              const message = error instanceof Error ? error.message : String(error);
              return { setting: setting.id, error: message };
            }
          }
          storeSetting(aether, setting.id, current);
          return { setting: setting.id, value: current };
        });
      }
    }
  };

  const registerPage = (force: boolean) => {
    let servers: Record<string, AetherJsonObject>;
    try {
      servers = readServers();
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      aether.notify(message, "error");
      servers = {};
    }
    const snapshot = readMcpAetherBridge()?.getSnapshot() ?? emptySnapshot();
    const pendingOAuth = Boolean(aether.storage.get<AetherJsonObject>(BRIDGE_OAUTH_KEY));
    const fingerprint = pageFingerprint(servers, snapshot, pendingOAuth);
    if (!force && fingerprint === lastFingerprint) return;
    lastFingerprint = fingerprint;

    const definition: AetherSettingsDefinition = {
      id: PAGE_ID,
      title: "MCP 服务器",
      subtitle: "管理 MCP 服务器，查看各传输配置，只保留需要保持活动的连接。",
      icon: "auto",
      order: 30,
      trailingIcon: "add",
      trailingCategory: "new-server",
      sections: buildMainSections(aether, servers, snapshot),
      categories: [
        {
          id: "new-server",
          title: "添加 MCP 服务器",
          subtitle: `写入 ${snapshot.configPath}`,
          icon: "auto",
          order: 10,
          trailingIcon: "none",
          hidden: true,
          sections: [newServerSection(aether)],
        },
        ...Object.keys(servers).sort().map((name, index) => serverCategory(aether, name, servers[name] as AetherJsonObject, index, snapshot)),
      ],
    };

    if (!seeded) {
      for (const section of [...(definition.sections ?? []), ...(definition.categories ?? []).flatMap((category) => category.sections)]) {
        for (const setting of section.settings) {
          if (setting.default === undefined) continue;
          const existing = aether.storage.get(settingStorageKey(setting.id)) ?? aether.storage.get(setting.id);
          const preserveDraft = setting.id.startsWith("new_") || setting.id === "oauth-input";
          if (!preserveDraft || existing === undefined || existing === null) {
            storeSetting(aether, setting.id, setting.default);
          }
        }
      }
      seeded = true;
    }

    unregisterSettings?.();
    try {
      unregisterSettings = aether.registerSettings(definition);
    } catch {
      unregisterSettings = aether.registerSettings({
        id: definition.id,
        title: definition.title,
        ...(definition.subtitle !== undefined ? { subtitle: definition.subtitle } : {}),
        ...(definition.icon !== undefined ? { icon: definition.icon } : {}),
        ...(definition.order !== undefined ? { order: definition.order } : {}),
        categories: [
          {
            id: "general",
            title: "MCP",
            subtitle: "运行时与 OAuth",
            icon: "auto",
            order: 1,
            sections: definition.sections ?? [],
          },
          ...(definition.categories ?? []),
        ],
      });
    }
    registerSettingActions(definition);
  };

  const scheduleRefresh = (force = false) => {
    if (refreshTimer) clearTimeout(refreshTimer);
    refreshTimer = setTimeout(() => {
      refreshTimer = undefined;
      registerPage(force);
    }, force ? 0 : 150);
  };

  aether.registerAction("mcp:toggle-server", async (payload) => {
    try {
      const id = asString(payload.setting);
      const serverName = id.startsWith("card_") ? id.slice("card_".length) : id;
      const checked = payload.checked !== undefined ? Boolean(payload.checked) : payload.value !== false;
      const servers = readServers();
      const entry = servers[serverName];
      if (!entry) throw new Error(`找不到服务器「${serverName}」。`);
      if (checked) {
        delete entry.disabled;
      } else {
        entry.disabled = true;
      }
      writeServers({ ...servers, [serverName]: entry });
      aether.notify(`MCP 服务器「${serverName}」已${checked ? "启用" : "停用"}。`, "info");
      scheduleRefresh(true);
      return { ok: true, name: serverName, enabled: checked };
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      aether.notify(`MCP: ${message}`, "error");
      return { ok: false, error: message };
    }
  });

  const handleInspect = async (serverName: string, kind: "tools" | "resources" | "prompts") => {
    try {
      const result = await withBridge((bridge) => bridge.inspect(serverName, kind), "Pi MCP 插件尚未加载。");
      const details = result.details || result.message;
      aether.storage.set(`mcp:inspect:${serverName}`, details);
      aether.notify(result.message, result.ok ? "info" : "warning");
      scheduleRefresh(true);
      return result;
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      aether.storage.set(`mcp:inspect:${serverName}`, `检查失败：${message}`);
      aether.notify(`MCP: ${message}`, "error");
      scheduleRefresh(true);
      return { ok: false, error: message };
    }
  };

  aether.registerAction("mcp:inspect-tools", async (payload) => {
    const name = validateServerName(asString(payload.serverName));
    return handleInspect(name, "tools");
  });

  aether.registerAction("mcp:inspect-resources", async (payload) => {
    const name = validateServerName(asString(payload.serverName));
    return handleInspect(name, "resources");
  });

  aether.registerAction("mcp:inspect-prompts", async (payload) => {
    const name = validateServerName(asString(payload.serverName));
    return handleInspect(name, "prompts");
  });

  aether.registerAction("mcp:add-server", async () => {
    try {
      const name = validateServerName(stored(aether, "new_name", ""));
      const transportRaw = stored(aether, "new_transport", "stdio");
      const transport: Transport = transportRaw === "http" ? "http" : transportRaw === "socket" ? "socket" : "stdio";
      const servers = readServers();
      if (servers[name]) throw new Error(`服务器「${name}」已存在。`);
      const entry = buildEntryFromForm(aether, name, transport);
      writeServers({ ...servers, [name]: entry });
      clearNewServerForm(aether);
      aether.notify(`已添加 MCP 服务器「${name}」。点「重新加载」后即可连接。`, "info");
      scheduleRefresh(true);
      return { ok: true, name };
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      aether.notify(`MCP: ${message}`, "error");
      return { ok: false, error: message };
    }
  });

  aether.registerAction("mcp:remove-server", async (payload) => {
    try {
      const name = validateServerName(asString(payload.serverName));
      const servers = readServers();
      if (!servers[name]) throw new Error(`服务器「${name}」在 ${configPath()} 中不存在。`);
      delete servers[name];
      writeServers(servers);
      clearServerStorage(aether, name);
      aether.notify(`已删除 MCP 服务器「${name}」。点「重新加载」后即可生效。`, "info");
      scheduleRefresh(true);
      return { ok: true, name };
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      aether.notify(`MCP: ${message}`, "error");
      return { ok: false, error: message };
    }
  });

  aether.registerAction("mcp:rename-server", async (payload) => {
    try {
      const name = validateServerName(asString(payload.serverName));
      const nextName = validateServerName(stored(aether, `server:${name}:renameTo`, ""));
      const servers = readServers();
      if (!servers[name]) throw new Error(`服务器「${name}」不存在。`);
      if (servers[nextName]) throw new Error(`服务器「${nextName}」已存在。`);
      writeServers({ ...Object.fromEntries(Object.entries(servers).filter(([key]) => key !== name)), [nextName]: servers[name] as AetherJsonObject });
      clearServerStorage(aether, name);
      aether.notify(`已将 MCP 服务器「${name}」重命名为「${nextName}」。点「重新加载」后即可生效。`, "info");
      scheduleRefresh(true);
      return { ok: true, name: nextName };
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      aether.notify(`MCP: ${message}`, "error");
      return { ok: false, error: message };
    }
  });

  const withBridge = async <T>(operation: (bridge: McpAetherBridge) => Promise<T>, missingMessage: string): Promise<T> => {
    const bridge = readMcpAetherBridge();
    if (!bridge) throw new Error(missingMessage);
    return operation(bridge);
  };

  aether.registerAction("mcp:reload", async () => {
    try {
      const result = await withBridge((bridge) => bridge.reload(), "Pi MCP 插件尚未加载。");
      if (result.ok) aether.notify("正在重新加载 MCP 插件。", "info");
      else aether.notify(`MCP: ${result.message}`, "warning");
      return result;
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      aether.notify(`MCP: ${message}`, "error");
      return { ok: false, error: message };
    }
  });

  aether.registerAction("mcp:reconnect-all", async () => {
    try {
      const result = await withBridge((bridge) => bridge.reconnectAll(), "Pi MCP 插件尚未加载。");
      aether.notify(result.message, result.ok ? "info" : "warning");
      scheduleRefresh(true);
      return result;
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      aether.notify(`MCP: ${message}`, "error");
      return { ok: false, error: message };
    }
  });

  aether.registerAction("mcp:reconnect-server", async (payload) => {
    try {
      const name = validateServerName(asString(payload.serverName));
      const result = await withBridge((bridge) => bridge.reconnect(name), "Pi MCP 插件尚未加载。");
      aether.notify(result.message, result.ok ? "info" : "warning");
      scheduleRefresh(true);
      return result;
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      aether.notify(`MCP: ${message}`, "error");
      return { ok: false, error: message };
    }
  });

  aether.registerAction("mcp:auth-start", async (payload) => {
    try {
      const name = validateServerName(asString(payload.serverName));
      const result = await withBridge((bridge) => bridge.startAuth(name), "Pi MCP 插件尚未加载。");
      if (result.ok && result.authorizationUrl) {
        aether.storage.set(BRIDGE_OAUTH_KEY, { serverName: name, authorizationUrl: result.authorizationUrl, startedAt: Date.now() });
        clearSetting(aether, "oauth-input");
        aether.notify("授权网址已就绪。打开并批准访问后，将回调 URL 粘贴到这里。", "info");
      } else {
        aether.notify(result.message, result.ok ? "info" : "warning");
      }
      scheduleRefresh(true);
      return result;
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      aether.notify(`MCP: ${message}`, "error");
      return { ok: false, error: message };
    }
  });

  aether.registerAction("mcp:oauth-complete", async (payload) => {
    try {
      const name = validateServerName(asString(payload.serverName));
      const input = stored(aether, "oauth-input", "").trim();
      if (!input) throw new Error("请先粘贴完整的回调 URL 或授权码。");
      const result = await withBridge((bridge) => bridge.completeAuth(name, input), "Pi MCP 插件尚未加载。");
      aether.storage.delete(BRIDGE_OAUTH_KEY);
      clearSetting(aether, "oauth-input");
      aether.notify(result.message, result.ok ? "info" : "warning");
      scheduleRefresh(true);
      return result;
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      aether.notify(`MCP: ${message}`, "error");
      return { ok: false, error: message };
    }
  });

  aether.registerAction("mcp:oauth-logout", async (payload) => {
    try {
      const name = validateServerName(asString(payload.serverName));
      const result = await withBridge((bridge) => bridge.logout(name), "Pi MCP 插件尚未加载。");
      aether.notify(result.message, result.ok ? "info" : "warning");
      scheduleRefresh(true);
      return result;
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      aether.notify(`MCP: ${message}`, "error");
      return { ok: false, error: message };
    }
  });


  registerPage(true);
  attachMcpAetherApi(aether, () => {
    syncDynamicToolTitles();
    scheduleRefresh(true);
  });

  return () => {
    if (refreshTimer) clearTimeout(refreshTimer);
    for (const cleanup of dynamicToolTitleCleanups.values()) cleanup();
    dynamicToolTitleCleanups.clear();
    unregisterSettings?.();
  };
};

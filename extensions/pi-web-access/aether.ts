import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";
import { getWebSearchConfigPath } from "./utils.ts";

type AetherJsonObject = Record<string, unknown>;
type AetherView = AetherJsonObject | AetherView[] | string | null | undefined;
type AetherRenderContext = AetherJsonObject & { storage: AetherJsonObject };
type AetherSettingDefinition = {
	id: string;
	label: string;
	description?: string;
	type?: "text" | "number" | "toggle" | "select" | "slider" | "password";
	default?: string | number | boolean;
	placeholder?: string;
	options?: Array<{ value: string; label: string }>;
	min?: number;
	max?: number;
	step?: number;
};
type AetherSettingsSection = {
	id?: string;
	title?: string;
	description?: string;
	settings: AetherSettingDefinition[];
};
type AetherSettingsCategory = {
	id: string;
	title: string;
	subtitle?: string;
	icon?: string;
	order?: number;
	sections: AetherSettingsSection[];
};
type AetherMessageTypeDefinition = {
	type: string;
	title?: string;
	icon?: string;
	render: AetherView | ((context: AetherRenderContext & { message: AetherJsonObject }) => AetherView | Promise<AetherView>);
};
type AetherExtensionAPI = {
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
	registerSettings(definition: {
		id: string;
		title: string;
		subtitle?: string;
		icon?: string;
		order?: number;
		sections?: AetherSettingsSection[];
		categories?: AetherSettingsCategory[];
	}): () => void;
	registerMessageType(definition: AetherMessageTypeDefinition): () => void;
	registerComposerMenuItem(definition: AetherJsonObject & { id: string; title: string }): () => void;
	registerSurface(slot: string, definition: AetherJsonObject & {
		render?: AetherView | ((context: AetherRenderContext) => AetherView | Promise<AetherView>);
	}): () => void;
	registerAction(id: string, handler: (payload: AetherJsonObject) => unknown | Promise<unknown>): () => void;
	registerToolTitle?(toolName: string, runningTitle: string, completedTitle: string, priority?: number): () => void;
};

const SETTINGS_PAGE_ID = "web-access-settings";
const BRIDGE_KEY = Symbol.for("pi-web-access.aether-bridge");

type SettingValue = string | number | boolean;
type WebAccessBridge = {
	api: AetherExtensionAPI;
};

type ConfigBinding = {
	setting: AetherSettingDefinition;
	path: string[];
	defaultValue: SettingValue;
	sensitive?: boolean;
	fromConfig?: (value: unknown) => SettingValue;
	toConfig?: (value: SettingValue) => unknown;
	apply?: (config: Record<string, unknown>, value: SettingValue) => void;
};

const providerOptions = [
	["auto", "自动"],
	["all", "所有可用服务商"],
	["openai", "OpenAI"],
	["brave", "Brave"],
	["parallel", "Parallel"],
	["tinyfish", "TinyFish"],
	["search1api", "Search1API"],
	["searchinfinity", "Searchinfinity"],
	["querit", "Querit"],
	["tavily", "Tavily"],
	["serpdive", "SERPdive"],
	["kagi", "Kagi"],
	["ollama", "Ollama Cloud"],
	["searxng", "SearXNG"],
	["exa", "Exa"],
	["perplexity", "Perplexity"],
	["gemini", "Gemini"],
	["anysearch", "AnySearch"],
	["xai", "xAI"],
	["brightdata", "Bright Data"],
	["serpbase", "SerpBase"],
].map(([value, label]) => ({ value, label }));

const routedProviders = providerOptions
	.map((option) => option.value)
	.filter((provider) => provider !== "auto" && provider !== "all");

function commaSeparated(value: unknown): string {
	return Array.isArray(value) ? value.filter((item): item is string => typeof item === "string").join(", ") : "";
}

function parseProviderRoute(value: SettingValue): string[] {
	const providers = String(value).split(",").map((part) => part.trim().toLowerCase()).filter(Boolean);
	const invalid = providers.find((provider) => !routedProviders.includes(provider));
	if (invalid) throw new Error(`回退路线中有未知的搜索服务商：${invalid}`);
	if (new Set(providers).size !== providers.length) throw new Error("顺序回退路线不能包含重复的服务商。");
	return providers;
}

const webSearchEnabledBinding: ConfigBinding = {
	setting: {
		id: "webSearchEnabled",
		label: "网页搜索工具",
		description: "下次重新加载插件后注册搜索和来源核对工具。",
		type: "toggle",
	},
	path: ["webSearch", "enabled"],
	defaultValue: true,
};

const providerBinding: ConfigBinding = {
	setting: {
		id: "provider",
		label: "默认搜索服务商",
		description: "工具调用将服务商留为「自动」时使用。",
		type: "select",
		options: providerOptions,
	},
	path: ["provider"],
	defaultValue: "auto",
	toConfig: (value) => value === "auto" ? "" : value,
};

const advancedCoreBindings: ConfigBinding[] = [
	{
		setting: {
			id: "searchRoutingProviders",
			label: "顺序回退路线",
			description: "按优先级用逗号分隔服务商。保存路线后会清除单一服务商覆盖，并默认在瞬时错误、额度不足和网络故障时回退。",
			type: "text",
			placeholder: "openai, brave, exa",
		},
		path: ["searchRouting", "providers"],
		defaultValue: "",
		fromConfig: commaSeparated,
		apply: (config, value) => {
			const providers = parseProviderRoute(value);
			if (providers.length === 0) {
				delete config.searchRouting;
				return;
			}
			const current = config.searchRouting && typeof config.searchRouting === "object" && !Array.isArray(config.searchRouting)
				? config.searchRouting as Record<string, unknown>
				: {};
			const fallbackOn = Array.isArray(current.fallbackOn) && current.fallbackOn.length > 0
				? current.fallbackOn
				: ["transient", "quota", "network"];
			config.searchRouting = { ...current, providers, fallbackOn };
			delete config.provider;
			delete config.searchProvider;
		},
	},
	{
		setting: {
			id: "workflow",
			label: "结果工作流",
			description: "审阅草稿、返回自动摘要，或返回原始结果。",
			type: "select",
			options: [
				{ value: "summary-review", label: "摘要审阅" },
				{ value: "auto-summary", label: "自动摘要" },
				{ value: "none", label: "原始结果" },
			],
		},
		path: ["workflow"],
		defaultValue: "summary-review",
	},
	{
		setting: {
			id: "curatorTimeoutSeconds",
			label: "审阅空闲超时",
			description: "审阅界面空闲多少秒后自动提交。",
			type: "number",
			min: 10,
			max: 600,
		},
		path: ["curatorTimeoutSeconds"],
		defaultValue: 20,
	},
	{
		setting: {
			id: "autoOpenBrowser",
			label: "自动打开审阅界面",
			description: "本地搜索开始时打开审阅界面。",
			type: "toggle",
		},
		path: ["autoOpenBrowser"],
		defaultValue: true,
	},
];

type ProviderSectionDefinition = {
	value: string;
	label: string;
	/** Setting id of the API key shown first in the Provider section, when the provider has one. */
	credentialId?: string;
	/** Setting id of the base URL shown after the API key, when the provider has one. */
	baseUrlId?: string;
	bindings: ConfigBinding[];
};

const credentialNames = [
	["openaiApiKey", "OpenAI", "OPENAI_API_KEY"], ["braveApiKey", "Brave", "BRAVE_API_KEY"],
	["parallelApiKey", "Parallel", "PARALLEL_API_KEY"], ["tinyfishApiKey", "TinyFish", "TINYFISH_API_KEY"],
	["search1apiApiKey", "Search1API", "SEARCH1API_KEY"], ["searchinfinityApiKey", "Searchinfinity", "SEARCHINFINITY_API_KEY"],
	["queritApiKey", "Querit", "QUERIT_API_KEY"], ["tavilyApiKey", "Tavily", "TAVILY_API_KEY"],
	["serpdiveApiKey", "SERPdive", "SERPDIVE_API_KEY"], ["kagiApiKey", "Kagi", "KAGI_API_KEY"],
	["ollamaApiKey", "Ollama Cloud", "OLLAMA_API_KEY"], ["serpbaseApiKey", "SerpBase", "SERPBASE_API_KEY"],
	["anysearchApiKey", "AnySearch", "ANYSEARCH_API_KEY"], ["xaiApiKey", "xAI", "XAI_API_KEY"],
	["brightdataApiKey", "Bright Data", "BRIGHTDATA_API_KEY"], ["firecrawlApiKey", "Firecrawl", "FIRECRAWL_API_KEY"],
	["exaApiKey", "Exa", "EXA_API_KEY"], ["perplexityApiKey", "Perplexity", "PERPLEXITY_API_KEY"],
	["geminiApiKey", "Gemini", "GEMINI_API_KEY"], ["cloudflareApiKey", "Cloudflare AI Gateway", "CLOUDFLARE_API_KEY"],
] as const;

function credentialBinding(id: string, options?: { label?: string; description?: string }): ConfigBinding {
	const match = credentialNames.find(([credentialId]) => credentialId === id);
	if (!match) throw new Error(`Unknown credential binding: ${id}`);
	const [credentialId, providerLabel, environmentVariable] = match;
	return {
		setting: {
			id: credentialId,
			label: options?.label ?? "API Key",
			description: options?.description
				?? `${providerLabel} 凭据。可填写密钥本身、$${environmentVariable}，或 !command 来源。此处显示当前已配置的值；清空即移除凭据。`,
			type: "password",
			placeholder: `密钥、$${environmentVariable} 或 !command`,
		},
		path: [credentialId],
		defaultValue: "",
		sensitive: true,
	};
}

const cloudflareApiKeyBinding: ConfigBinding = credentialBinding("cloudflareApiKey", {
	label: "Cloudflare AI Gateway 密钥",
	description: "配置了 Gemini 网关 Base URL 时使用的 Cloudflare AI Gateway 凭据。可填写密钥本身、$CLOUDFLARE_API_KEY，或 !command 来源。",
});

const summaryModelBinding: ConfigBinding = {
	setting: {
		id: "summaryModel",
		label: "摘要模型",
		description: "可选的服务商/模型 ID。留空则使用当前已启用的最佳模型。",
		type: "text",
		placeholder: "provider/model-id",
	},
	path: ["summaryModel"],
	defaultValue: "",
};

const geminiSearchModelBinding: ConfigBinding = {
	setting: {
		id: "searchModel",
		label: "Gemini 搜索模型",
		description: "可选的 Gemini 接地搜索模型覆盖。",
		type: "text",
		placeholder: "gemini-3.6-flash",
	},
	path: ["searchModel"],
	defaultValue: "",
};

function baseUrlBinding(id: string, providerLabel: string, description: string, placeholder: string): ConfigBinding {
	return {
		setting: {
			id,
			label: "基础 URL",
			description,
			type: "text",
			placeholder,
		},
		path: [id],
		defaultValue: "",
	};
}

const openaiBaseUrlBinding = baseUrlBinding(
	"openaiResponsesUrl",
	"OpenAI",
	"可选的 Responses 兼容端点覆盖。留空则使用默认端点。",
	"https://api.openai.com/v1/responses",
);

const braveBaseUrlBinding = baseUrlBinding(
	"braveBaseUrl",
	"Brave",
	"可选的端点覆盖。留空则使用默认端点。",
	"https://api.search.brave.com/res/v1/web/search",
);

const parallelBaseUrlBinding = baseUrlBinding(
	"parallelBaseUrl",
	"Parallel",
	"可选的网关覆盖；搜索和提取请求会发到这里。留空则使用默认端点。",
	"https://api.parallel.ai",
);

const tinyfishBaseUrlBinding = baseUrlBinding(
	"tinyfishBaseUrl",
	"TinyFish",
	"可选的网关覆盖；搜索和抓取请求会发到这里。留空则使用默认端点。",
	"https://api.search.tinyfish.ai",
);

const search1apiBaseUrlBinding = baseUrlBinding(
	"search1apiBaseUrl",
	"Search1API",
	"可选的网关覆盖；搜索和抓取请求会发到这里。留空则使用默认端点。",
	"https://api.search1api.com",
);

const searchinfinityBaseUrlBinding = baseUrlBinding(
	"searchinfinityBaseUrl",
	"Searchinfinity",
	"可选的端点覆盖。留空则使用默认端点。",
	"https://torchlight.byteintlapi.com/search_api/web_search",
);

const queritBaseUrlBinding = baseUrlBinding(
	"queritBaseUrl",
	"Querit",
	"可选的网关覆盖；搜索和正文请求会发到这里。留空则使用默认端点。",
	"https://api.querit.ai",
);

const tavilyBaseUrlBinding = baseUrlBinding(
	"tavilyBaseUrl",
	"Tavily",
	"可选的端点覆盖。留空则使用默认端点。",
	"https://api.tavily.com/search",
);

const serpdiveBaseUrlBinding = baseUrlBinding(
	"serpdiveBaseUrl",
	"SERPdive",
	"可选的端点覆盖。留空则使用默认端点。",
	"https://api.serpdive.com/v1/search",
);

const kagiBaseUrlBinding = baseUrlBinding(
	"kagiBaseUrl",
	"Kagi",
	"可选的网关覆盖；搜索和提取请求会发到这里。留空则使用默认端点。",
	"https://kagi.com",
);

const ollamaBaseUrlBinding = baseUrlBinding(
	"ollamaBaseUrl",
	"Ollama Cloud",
	"可选的网关覆盖；搜索和抓取请求会发到这里。留空则使用默认端点。",
	"https://ollama.com",
);

const exaBaseUrlBinding = baseUrlBinding(
	"exaBaseUrl",
	"Exa",
	"可选的网关覆盖，用于直接回答和搜索 API；零配置的 Exa MCP 工具不受影响。留空则使用默认端点。",
	"https://api.exa.ai",
);

const perplexityBaseUrlBinding = baseUrlBinding(
	"perplexityBaseUrl",
	"Perplexity",
	"可选的端点覆盖。留空则使用默认端点。",
	"https://api.perplexity.ai/chat/completions",
);

const anysearchBaseUrlBinding = baseUrlBinding(
	"anysearchBaseUrl",
	"AnySearch",
	"可选的端点覆盖。留空则使用默认端点。",
	"https://api.anysearch.com/v1/search",
);

const xaiBaseUrlBinding = baseUrlBinding(
	"xaiBaseUrl",
	"xAI",
	"可选的 Responses 兼容端点覆盖。留空则使用默认端点。",
	"https://api.x.ai/v1/responses",
);

const brightdataBaseUrlBinding = baseUrlBinding(
	"brightdataBaseUrl",
	"Bright Data",
	"可选的端点覆盖。留空则使用默认端点。",
	"https://api.brightdata.com/request",
);

const serpbaseBaseUrlBinding = baseUrlBinding(
	"serpbaseBaseUrl",
	"SerpBase",
	"可选的端点覆盖。留空则使用默认端点。",
	"https://api.serpbase.dev/google/search",
);

const geminiBaseUrlBinding = baseUrlBinding(
	"geminiBaseUrl",
	"Gemini",
	"可选的裸网关 URL，不要带 API 版本后缀。留空则使用默认端点。",
	"https://generativelanguage.googleapis.com",
);

const searxngBaseUrlBinding = baseUrlBinding(
	"searxngBaseUrl",
	"SearXNG",
	"自托管的 SearXNG 搜索实例。配置后搜索会优先发到这里。",
	"https://search.example.com",
);

const firecrawlBaseUrlBinding = baseUrlBinding(
	"firecrawlBaseUrl",
	"Firecrawl",
	"可选的兼容防火墙的 Firecrawl 服务器，用于提取被拦截的内容。",
	"https://api.firecrawl.dev",
);

const openaiSearchModelBinding: ConfigBinding = {
	setting: {
		id: "openaiSearchModel",
		label: "OpenAI 搜索模型",
		description: "OpenAI Responses 网页搜索的可选模型 ID。",
		type: "text",
		placeholder: "gpt-5.6-terra",
	},
	path: ["openaiSearchModel"],
	defaultValue: "",
};

const xaiSearchModelBinding: ConfigBinding = {
	setting: {
		id: "xaiSearchModel",
		label: "xAI 搜索模型",
		description: "xAI 托管网页搜索的可选 Grok 模型 ID。",
		type: "text",
		placeholder: "grok-4.5",
	},
	path: ["xaiSearchModel"],
	defaultValue: "",
};

const serpdiveModelBinding: ConfigBinding = {
	setting: {
		id: "serpdiveModel",
		label: "SERPdive 检索深度",
		description: "Krill 是免费档；Mako 和 Moby 会消耗付费额度。",
		type: "select",
		options: [
			{ value: "krill", label: "Krill - 免费" },
			{ value: "mako", label: "Mako - 精选" },
			{ value: "moby", label: "Moby - 全文" },
		],
	},
	path: ["serpdiveModel"],
	defaultValue: "krill",
};

const githubCloneEnabledBinding: ConfigBinding = {
	setting: {
		id: "githubCloneEnabled",
		label: "克隆 GitHub 仓库",
		description: "允许按仓库理解 GitHub URL 并提取内容。",
		type: "toggle",
	},
	path: ["githubClone", "enabled"],
	defaultValue: true,
};

const githubMaxRepoSizeMBBinding: ConfigBinding = {
	setting: {
		id: "githubMaxRepoSizeMB",
		label: "GitHub 克隆大小上限",
		description: "超过此大小的仓库改用轻量 API 视图。",
		type: "number",
		min: 1,
	},
	path: ["githubClone", "maxRepoSizeMB"],
	defaultValue: 350,
};

const githubCloneTimeoutSecondsBinding: ConfigBinding = {
	setting: {
		id: "githubCloneTimeoutSeconds",
		label: "GitHub 克隆超时",
		description: "克隆仓库允许的最长秒数。",
		type: "number",
		min: 1,
		max: 600,
	},
	path: ["githubClone", "cloneTimeoutSeconds"],
	defaultValue: 30,
};

const githubClonePathBinding: ConfigBinding = {
	setting: {
		id: "githubClonePath",
		label: "GitHub 克隆缓存路径",
		description: "临时仓库克隆的运行时绝对路径。",
		type: "text",
		placeholder: "/tmp/pi-github-repos",
	},
	path: ["githubClone", "clonePath"],
	defaultValue: "/tmp/pi-github-repos",
};

const youtubeEnabledBinding: ConfigBinding = {
	setting: {
		id: "youtubeEnabled",
		label: "理解 YouTube",
		description: "在可用时提取字幕并分析视频。",
		type: "toggle",
	},
	path: ["youtube", "enabled"],
	defaultValue: true,
};

const youtubePreferredModelBinding: ConfigBinding = {
	setting: {
		id: "youtubePreferredModel",
		label: "YouTube 模型",
		description: "用于字幕和视频理解的首选 Gemini 模型。",
		type: "text",
		placeholder: "gemini-3.6-flash",
	},
	path: ["youtube", "preferredModel"],
	defaultValue: "gemini-3.6-flash",
};

const videoEnabledBinding: ConfigBinding = {
	setting: {
		id: "videoEnabled",
		label: "本地视频分析",
		description: "允许分析支持的本地视频文件。",
		type: "toggle",
	},
	path: ["video", "enabled"],
	defaultValue: true,
};

const videoPreferredModelBinding: ConfigBinding = {
	setting: {
		id: "videoPreferredModel",
		label: "本地视频模型",
		description: "用于本地视频分析的首选 Gemini 模型。",
		type: "text",
		placeholder: "gemini-3.6-flash",
	},
	path: ["video", "preferredModel"],
	defaultValue: "gemini-3.6-flash",
};

const videoMaxSizeMBBinding: ConfigBinding = {
	setting: {
		id: "videoMaxSizeMB",
		label: "本地视频大小上限",
		description: "本地视频上传的最大体积，单位 MB。",
		type: "number",
		min: 1,
	},
	path: ["video", "maxSizeMB"],
	defaultValue: 50,
};

const pdfMaxSizeMBBinding: ConfigBinding = {
	setting: {
		id: "pdfMaxSizeMB",
		label: "PDF 大小上限",
		description: "PDF 下载的最大体积，单位 MB。",
		type: "number",
		min: 1,
		max: 50,
	},
	path: ["pdf", "maxSizeMB"],
	defaultValue: 20,
};

const firecrawlFreshScrapeBinding: ConfigBinding = {
	setting: {
		id: "firecrawlFreshScrape",
		label: "允许 Firecrawl 即时抓取",
		description: "允许已配置的 Firecrawl 服务器抓取尚未缓存的目标。",
		type: "toggle",
	},
	path: ["firecrawlFreshScrape"],
	defaultValue: false,
};

const allowBrowserCookiesBinding: ConfigBinding = {
	setting: {
		id: "allowBrowserCookies",
		label: "Gemini 浏览器 Cookie",
		description: "允许只读发现 Chromium Cookie，供 Gemini Web 使用。",
		type: "toggle",
	},
	path: ["allowBrowserCookies"],
	defaultValue: false,
};

const chromeProfileBinding: ConfigBinding = {
	setting: {
		id: "chromeProfile",
		label: "Chromium 配置文件",
		description: "用于 Gemini Web Cookie 发现的可选配置文件名。",
		type: "text",
		placeholder: "Profile 2",
	},
	path: ["chromeProfile"],
	defaultValue: "",
};

const trustEnvProxyBinding: ConfigBinding = {
	setting: {
		id: "trustEnvProxy",
		label: "信任已配置的环境代理",
		description: "仅在适用 HTTP(S) 代理时跳过主机名 DNS 预检。",
		type: "toggle",
	},
	path: ["ssrf", "trustEnvProxy"],
	defaultValue: false,
};

const ssrfAllowRangesBinding: ConfigBinding = {
	setting: {
		id: "ssrfAllowRanges",
		label: "允许的代理网段",
		description: "用逗号分隔的 CIDR，用于窄范围伪造 IP 或私有代理网段。",
		type: "text",
		placeholder: "198.18.0.0/15, fd00::/8",
	},
	path: ["ssrf", "allowRanges"],
	defaultValue: "",
	fromConfig: (value) => Array.isArray(value) ? value.join(", ") : "",
	toConfig: (value) => String(value).split(",").map((part) => part.trim()).filter(Boolean),
};

const fetchDomainAllowBinding: ConfigBinding = {
	setting: {
		id: "fetchDomainAllow",
		label: "允许抓取的域名",
		description: "可选的逗号分隔白名单，用于 fetch_content。",
		type: "text",
		placeholder: "docs.example.com, github.com",
	},
	path: ["fetchContent", "domainPolicy", "allow"],
	defaultValue: "",
	fromConfig: commaSeparated,
	toConfig: (value) => String(value).split(",").map((part) => part.trim()).filter(Boolean),
};

const fetchDomainDenyBinding: ConfigBinding = {
	setting: {
		id: "fetchDomainDeny",
		label: "禁止抓取的域名",
		description: "可选的逗号分隔黑名单；拒绝规则优先。",
		type: "text",
		placeholder: "internal.example.com",
	},
	path: ["fetchContent", "domainPolicy", "deny"],
	defaultValue: "",
	fromConfig: commaSeparated,
	toConfig: (value) => String(value).split(",").map((part) => part.trim()).filter(Boolean),
};

const firecrawlApiVersionBinding: ConfigBinding = {
	setting: {
		id: "firecrawlApiVersion",
		label: "Firecrawl API 版本",
		description: "仅在较旧的自托管部署上使用 v1。",
		type: "select",
		options: [{ value: "v2", label: "v2" }, { value: "v1", label: "v1" }],
	},
	path: ["firecrawlApiVersion"],
	defaultValue: "v2",
};

const brightdataSerpZoneBinding: ConfigBinding = {
	setting: {
		id: "brightdataSerpZone",
		label: "Bright Data SERP Zone",
		description: "付费 SERP 搜索所需的 Bright Data serp 类型 Zone。",
		type: "text",
		placeholder: "pi_serp",
	},
	path: ["brightdataSerpZone"],
	defaultValue: "",
};

const brightdataUnlockerZoneBinding: ConfigBinding = {
	setting: {
		id: "brightdataUnlockerZone",
		label: "Bright Data Unlocker Zone",
		description: "付费抓取回退所需的 Bright Data unblocker 类型 Zone。",
		type: "text",
		placeholder: "pi_unlocker",
	},
	path: ["brightdataUnlockerZone"],
	defaultValue: "",
};

const providerSections: ProviderSectionDefinition[] = [
	{
		value: "openai", label: "OpenAI", credentialId: "openaiApiKey", baseUrlId: "openaiResponsesUrl",
		bindings: [
			credentialBinding("openaiApiKey"), openaiBaseUrlBinding, openaiSearchModelBinding,
		],
	},
	{
		value: "brave", label: "Brave", credentialId: "braveApiKey", baseUrlId: "braveBaseUrl",
		bindings: [credentialBinding("braveApiKey"), braveBaseUrlBinding],
	},
	{
		value: "parallel", label: "Parallel", credentialId: "parallelApiKey", baseUrlId: "parallelBaseUrl",
		bindings: [credentialBinding("parallelApiKey"), parallelBaseUrlBinding],
	},
	{
		value: "tinyfish", label: "TinyFish", credentialId: "tinyfishApiKey", baseUrlId: "tinyfishBaseUrl",
		bindings: [credentialBinding("tinyfishApiKey"), tinyfishBaseUrlBinding],
	},
	{
		value: "search1api", label: "Search1API", credentialId: "search1apiApiKey", baseUrlId: "search1apiBaseUrl",
		bindings: [credentialBinding("search1apiApiKey"), search1apiBaseUrlBinding],
	},
	{
		value: "searchinfinity", label: "Searchinfinity", credentialId: "searchinfinityApiKey", baseUrlId: "searchinfinityBaseUrl",
		bindings: [credentialBinding("searchinfinityApiKey"), searchinfinityBaseUrlBinding],
	},
	{
		value: "querit", label: "Querit", credentialId: "queritApiKey", baseUrlId: "queritBaseUrl",
		bindings: [credentialBinding("queritApiKey"), queritBaseUrlBinding],
	},
	{
		value: "tavily", label: "Tavily", credentialId: "tavilyApiKey", baseUrlId: "tavilyBaseUrl",
		bindings: [credentialBinding("tavilyApiKey"), tavilyBaseUrlBinding],
	},
	{
		value: "serpdive", label: "SERPdive", credentialId: "serpdiveApiKey", baseUrlId: "serpdiveBaseUrl",
		bindings: [credentialBinding("serpdiveApiKey"), serpdiveBaseUrlBinding, serpdiveModelBinding],
	},
	{
		value: "kagi", label: "Kagi", credentialId: "kagiApiKey", baseUrlId: "kagiBaseUrl",
		bindings: [credentialBinding("kagiApiKey"), kagiBaseUrlBinding],
	},
	{
		value: "ollama", label: "Ollama Cloud", credentialId: "ollamaApiKey", baseUrlId: "ollamaBaseUrl",
		bindings: [credentialBinding("ollamaApiKey"), ollamaBaseUrlBinding],
	},
	{
		value: "searxng", label: "SearXNG", baseUrlId: "searxngBaseUrl",
		bindings: [searxngBaseUrlBinding],
	},
	{
		value: "exa", label: "Exa", credentialId: "exaApiKey", baseUrlId: "exaBaseUrl",
		bindings: [credentialBinding("exaApiKey"), exaBaseUrlBinding],
	},
	{
		value: "perplexity", label: "Perplexity", credentialId: "perplexityApiKey", baseUrlId: "perplexityBaseUrl",
		bindings: [credentialBinding("perplexityApiKey"), perplexityBaseUrlBinding],
	},
	{
		value: "gemini", label: "Gemini", credentialId: "geminiApiKey", baseUrlId: "geminiBaseUrl",
		bindings: [
			credentialBinding("geminiApiKey"), geminiBaseUrlBinding, cloudflareApiKeyBinding,
			geminiSearchModelBinding, allowBrowserCookiesBinding, chromeProfileBinding,
		],
	},
	{
		value: "anysearch", label: "AnySearch", credentialId: "anysearchApiKey", baseUrlId: "anysearchBaseUrl",
		bindings: [credentialBinding("anysearchApiKey"), anysearchBaseUrlBinding],
	},
	{
		value: "xai", label: "xAI", credentialId: "xaiApiKey", baseUrlId: "xaiBaseUrl",
		bindings: [credentialBinding("xaiApiKey"), xaiBaseUrlBinding, xaiSearchModelBinding],
	},
	{
		value: "brightdata", label: "Bright Data", credentialId: "brightdataApiKey", baseUrlId: "brightdataBaseUrl",
		bindings: [
			credentialBinding("brightdataApiKey"), brightdataBaseUrlBinding,
			brightdataSerpZoneBinding, brightdataUnlockerZoneBinding,
		],
	},
	{
		value: "serpbase", label: "SerpBase", credentialId: "serpbaseApiKey", baseUrlId: "serpbaseBaseUrl",
		bindings: [credentialBinding("serpbaseApiKey"), serpbaseBaseUrlBinding],
	},
	{
		value: "firecrawl", label: "Firecrawl", credentialId: "firecrawlApiKey", baseUrlId: "firecrawlBaseUrl",
		bindings: [
			credentialBinding("firecrawlApiKey"), firecrawlBaseUrlBinding, firecrawlApiVersionBinding,
			firecrawlFreshScrapeBinding,
		],
	},
];

const githubBindings: ConfigBinding[] = [
	githubCloneEnabledBinding,
	githubMaxRepoSizeMBBinding,
	githubCloneTimeoutSecondsBinding,
	githubClonePathBinding,
];

const youtubeBindings: ConfigBinding[] = [
	youtubeEnabledBinding,
	youtubePreferredModelBinding,
	videoEnabledBinding,
	videoPreferredModelBinding,
	videoMaxSizeMBBinding,
];

const pdfBindings: ConfigBinding[] = [
	pdfMaxSizeMBBinding,
];

const privacyBindings: ConfigBinding[] = [
	trustEnvProxyBinding,
	ssrfAllowRangesBinding,
	fetchDomainAllowBinding,
	fetchDomainDenyBinding,
];

/** Everything on the Provider page that is not provider-specific lands in Advanced. */
const advancedBindings: ConfigBinding[] = [
	...advancedCoreBindings,
	summaryModelBinding,
];

function providerSection(provider: string): AetherSettingsSection & { bindings: ConfigBinding[] } {
	const section = providerSections.find((item) => item.value === provider);
	const bindings: ConfigBinding[] = [providerBinding];
	if (section) {
		const credential = section.credentialId
			? section.bindings.find((binding) => binding.setting.id === section.credentialId)
			: undefined;
		const baseUrl = section.baseUrlId
			? section.bindings.find((binding) => binding.setting.id === section.baseUrlId)
			: undefined;
		bindings.push(
			...(credential ? [credential] : []),
			...(baseUrl ? [baseUrl] : []),
			...section.bindings.filter((binding) => binding !== credential && binding !== baseUrl),
		);
	}
	return {
		id: "provider",
		title: "服务商",
		description: section
			? `默认服务商及 ${section.label} 配置。凭据保存在现有的 Pi 配置文件中。`
			: "在上方选择具体服务商，以配置其 API Key、Base URL 和专属选项。",
		settings: bindings.map((item) => item.setting),
		bindings,
	};
}

const toolsSection: AetherSettingsSection = {
	id: "tools",
	title: "网页搜索工具",
	description: "下次重新加载插件后注册搜索和来源核对工具。",
	settings: [webSearchEnabledBinding.setting],
};

/** Fallback layout for Aether builds that do not render page-level sections yet. */
const generalCategory: AetherSettingsCategory = {
	id: "general",
	title: "网页搜索工具",
	subtitle: "网页搜索和来源核对的总开关",
	icon: "auto",
	order: 0,
	sections: [toolsSection],
};

const extractionCategory: AetherSettingsCategory = {
	id: "extraction",
	title: "内容提取",
	subtitle: "GitHub、视频和 PDF 处理",
	icon: "code",
	order: 2,
	sections: [
		{
			id: "github",
			title: "GitHub",
			description: "仓库克隆、缓存路径和大小上限",
			settings: githubBindings.map((item) => item.setting),
		},
		{
			id: "youtube",
			title: "YouTube",
			description: "YouTube 与本地文件的字幕和视频理解",
			settings: youtubeBindings.map((item) => item.setting),
		},
		{
			id: "pdf",
			title: "PDF",
			description: "PDF 下载上限",
			settings: pdfBindings.map((item) => item.setting),
		},
	],
};

const privacyCategory: AetherSettingsCategory = {
	id: "privacy",
	title: "隐私与网络",
	subtitle: "浏览器数据访问、SSRF 例外和抓取域名策略",
	icon: "info",
	order: 3,
	sections: [{
		id: "privacy",
		title: "隐私与网络",
		description: "SSRF 例外和抓取域名策略",
		settings: privacyBindings.map((item) => item.setting),
	}],
};

function categoriesForProvider(provider: string): AetherSettingsCategory[] {
	return [
		{
			id: "provider",
			title: "服务商",
			subtitle: "默认搜索服务商、凭据和基础 URL",
			icon: "auto",
			order: 1,
			sections: [
				providerSection(provider),
				{
					id: "advanced",
					title: "高级",
					description: "路由、审阅工作流和摘要模型",
					settings: advancedBindings.map((item) => item.setting),
				},
			],
		},
		extractionCategory,
		privacyCategory,
	];
}

const allBindings = Array.from(
	new Map([
		webSearchEnabledBinding,
		providerBinding,
		...advancedBindings,
		...githubBindings,
		...youtubeBindings,
		...pdfBindings,
		...privacyBindings,
		...providerSections.flatMap((section) => section.bindings),
	].map((binding) => [binding.setting.id, binding])).values(),
);

function readConfig(): Record<string, unknown> {
	const path = getWebSearchConfigPath();
	if (!existsSync(path)) return {};
	const value = JSON.parse(readFileSync(path, "utf8"));
	if (!value || typeof value !== "object" || Array.isArray(value)) {
		throw new Error(`Invalid config in ${path}: expected a JSON object`);
	}
	return value;
}

function writeConfig(config: Record<string, unknown>): void {
	const path = getWebSearchConfigPath();
	mkdirSync(dirname(path), { recursive: true });
	const temporaryPath = `${path}.${process.pid}.aether.tmp`;
	writeFileSync(temporaryPath, `${JSON.stringify(config, null, 2)}\n`, { encoding: "utf8", mode: 0o600 });
	renameSync(temporaryPath, path);
}

function getAtPath(root: Record<string, unknown>, path: string[]): unknown {
	let value: unknown = root;
	for (const segment of path) {
		if (!value || typeof value !== "object" || Array.isArray(value)) return undefined;
		value = (value as Record<string, unknown>)[segment];
	}
	return value;
}

function setAtPath(root: Record<string, unknown>, path: string[], value: unknown): void {
	let target = root;
	for (const segment of path.slice(0, -1)) {
		const current = target[segment];
		if (!current || typeof current !== "object" || Array.isArray(current)) target[segment] = {};
		target = target[segment] as Record<string, unknown>;
	}
	const key = path.at(-1)!;
	if (value === "" || (Array.isArray(value) && value.length === 0)) delete target[key];
	else target[key] = value;
}

function normalizeSettingValue(binding: ConfigBinding, raw: unknown): SettingValue {
	const setting = binding.setting;
	if (setting.type === "toggle") return raw === true || raw === "true";
	if (setting.type === "number" || setting.type === "slider") {
		const fallback = Number(binding.defaultValue);
		const parsed = typeof raw === "number" ? raw : Number(raw);
		return Math.min(setting.max ?? parsed, Math.max(setting.min ?? parsed, Number.isFinite(parsed) ? parsed : fallback));
	}
	if (setting.type === "select") {
		const candidate = String(raw ?? "");
		return setting.options?.some((option) => option.value === candidate) ? candidate : binding.defaultValue;
	}
	return String(raw ?? "").trim();
}

function initialProvider(config: Record<string, unknown>, storage: AetherJsonObject): string {
	const stored = storage.provider;
	if (typeof stored === "string" && providerSections.some((section) => section.value === stored)) return stored;
	const configured = typeof config.provider === "string"
		? config.provider
		: typeof config.searchProvider === "string"
			? config.searchProvider
			: "";
	if (providerSections.some((section) => section.value === configured)) return configured;
	return "auto";
}

function messageText(message: AetherJsonObject): string {
	const direct = typeof message.text === "string" ? message.text : typeof message.content === "string" ? message.content : "";
	if (direct) return direct;
	if (!Array.isArray(message.content)) return "";
	return message.content.map((part) => {
		if (!part || typeof part !== "object") return "";
		return typeof (part as AetherJsonObject).text === "string" ? (part as AetherJsonObject).text : "";
	}).join("\n");
}

function statusCard(api: AetherExtensionAPI, title: string, message: AetherJsonObject, tone = "neutral") {
	const text = messageText(message);
	const details = message.details && typeof message.details === "object" ? message.details as AetherJsonObject : message;
	const chips = [
		typeof details.provider === "string" ? details.provider : "",
		typeof details.sourceCount === "number" ? `${details.sourceCount} 个来源` : "",
		typeof details.totalResults === "number" ? `${details.totalResults} 条结果` : "",
		typeof details.successfulQueries === "number" && typeof details.queryCount === "number" ? `${details.successfulQueries}/${details.queryCount} 次查询` : "",
		typeof details.successful === "number" && typeof details.total === "number" ? `${details.successful}/${details.total}` : "",
	].filter(Boolean);
	return api.ui.card([
		api.ui.row([
			api.ui.text(title, { style: "label", weight: 1, color: tone === "error" ? "error" : "accent" }),
			...chips.map((chip) => api.ui.text(String(chip), { style: "label", color: "muted" })),
		], { arrangement: "space-between", verticalAlignment: "center", wrap: true, rowSpacing: 6 }),
		...(text ? [api.ui.text(text, { color: tone === "error" ? "error" : "default", maxLines: 12 })] : []),
	], { tone, radius: 8, spacing: 8, contentPadding: 14 });
}

function messageTypes(api: AetherExtensionAPI): AetherMessageTypeDefinition[] {
	return [
		{ type: "web-search-results", title: "网页研究", icon: "auto", render: ({ message }) => statusCard(api, "网页研究", message) },
		{ type: "web-search-content-ready", title: "网页内容已就绪", icon: "refresh", render: ({ message }) => statusCard(api, "网页内容已就绪", message) },
		{ type: "web-search-error", title: "网页访问出错", icon: "warning", render: ({ message }) => statusCard(api, "网页访问出错", message, "error") },
		{ type: "curator-config", title: "搜索工作流", icon: "settings", render: ({ message }) => statusCard(api, "搜索工作流已更新", message) },
		{ type: "google-account", title: "Gemini Web 账号", icon: "info", render: ({ message }) => statusCard(api, "Gemini Web 账号", message) },
	];
}

export async function appendAetherWebMessage(
	type: string,
	payload: AetherJsonObject,
	text = "",
): Promise<boolean> {
	const bridge = (globalThis as Record<PropertyKey, unknown>)[BRIDGE_KEY] as WebAccessBridge | undefined;
	if (!bridge) return false;
	try {
		const latest = { type, payload, text, at: Date.now() };
		bridge.api.storage.set("latestActivity", latest);
		await bridge.api.messages.append(type, payload, text);
		return true;
	} catch {
		return false;
	}
}

const webToolTitles = [
	["web_search", "正在搜索网页", "已搜索网页"],
	["source_check", "正在核对来源", "已核对来源"],
	["fetch_content", "正在抓取网页内容", "已抓取网页内容"],
	["get_search_content", "正在阅读网页内容", "已阅读网页内容"],
] as const;

export const activateAether = async (aether: AetherExtensionAPI) => {
	(globalThis as Record<PropertyKey, unknown>)[BRIDGE_KEY] = { api: aether } satisfies WebAccessBridge;
	for (const [toolName, runningTitle, completedTitle] of webToolTitles) {
		aether.registerToolTitle?.(toolName, runningTitle, completedTitle, 200);
	}
	let config: Record<string, unknown> = {};
	try {
		config = readConfig();
	} catch (error) {
		const message = error instanceof Error ? error.message : String(error);
		aether.host.invoke("app.notify", { message: `网页访问设置无法读取 Pi 配置：${message}` }).catch(() => {});
	}
	const storage = aether.storage.snapshot();
	const settingStorageKey = (settingId: string) => `settings:${SETTINGS_PAGE_ID}:${settingId}`;
	for (const binding of allBindings) {
		if (binding.sensitive) {
			// Credentials are shown back in the form (masked), so always reflect
			// the currently configured value instead of a one-time seed.
			const configured = getAtPath(config, binding.path);
			const current = configured === undefined || configured === null ? "" : String(configured);
			aether.storage.set(binding.setting.id, current);
			aether.storage.set(settingStorageKey(binding.setting.id), current);
			continue;
		}
		if (Object.prototype.hasOwnProperty.call(storage, binding.setting.id)) continue;
		const configured = binding.setting.id === "provider"
			? config.provider ?? config.searchProvider
			: getAtPath(config, binding.path);
		const initial = configured === undefined
			? binding.defaultValue
			: binding.fromConfig?.(configured) ?? normalizeSettingValue(binding, configured);
		aether.storage.set(binding.setting.id, initial);
	}

	const registerSettingsPage = (providerValue: string) => {
		const definition = {
			id: SETTINGS_PAGE_ID,
			title: "网页访问",
			subtitle: "搜索、来源核对、内容提取和服务商路由",
			icon: "auto",
			order: 20,
			sections: [toolsSection],
			categories: categoriesForProvider(providerValue),
		};
		try {
			aether.registerSettings(definition);
		} catch {
			// Older Aether builds only accept sections OR categories. Fall back
			// to the master toggle as its own top-level category.
			aether.registerSettings({
				...definition,
				sections: undefined,
				categories: [generalCategory, ...categoriesForProvider(providerValue)],
			});
		}
	};

	const registerBindingActions = () => {
		for (const binding of allBindings) {
			aether.registerAction(`settings:${SETTINGS_PAGE_ID}:${binding.setting.id}`, async (payload) => {
				const raw = payload.value !== undefined ? payload.value : payload.checked;
				const value = normalizeSettingValue(binding, raw);
				const next = readConfig();
				if (binding.apply) binding.apply(next, value);
				else setAtPath(next, binding.path, binding.toConfig?.(value) ?? value);
				writeConfig(next);
				if (binding.sensitive) {
					aether.storage.set(binding.setting.id, value);
					aether.storage.set(settingStorageKey(binding.setting.id), value);
					await aether.host.invoke("app.notify", { message: "凭据或 Base URL 已更新。重新加载 Pi 插件后生效。" }).catch(() => {});
					return { setting: binding.setting.id, value };
				}
				aether.storage.set(binding.setting.id, value);
				aether.storage.set(settingStorageKey(binding.setting.id), value);
				if (binding.setting.id === "provider") {
					registerSettingsPage(String(value));
				}
				if (binding.setting.id === "searchRoutingProviders" && value !== "") {
					aether.storage.set("provider", "auto");
					aether.storage.set(settingStorageKey("provider"), "auto");
					registerSettingsPage("auto");
				}
				await aether.host.invoke("app.notify", { message: "网页访问设置已保存。重新加载 Pi 插件后生效。" }).catch(() => {});
				return { setting: binding.setting.id, value };
			});
		}
	};

	registerBindingActions();
	registerSettingsPage(initialProvider(config, storage));

	for (const definition of messageTypes(aether)) aether.registerMessageType(definition);

	aether.registerAction("dismiss-latest-activity", () => aether.storage.delete("latestActivity"));
	aether.registerAction("research-draft", async () => {
		await aether.host.invoke("app.appendDraftInput", { text: "请用多个独立来源在网上研究：" });
	});
	aether.registerComposerMenuItem({
		id: "research-web",
		title: "在网页上研究",
		subtitle: "起草一份多来源研究请求",
		icon: "auto",
		order: 30,
		action: "research-draft",
	});
	aether.registerSurface("chat.list.end", {
		id: "latest-web-activity",
		order: 90,
		render: (context) => {
			const currentStorage = context.storage;
			if (Array.isArray(context.custom_messages)) return null;
			const latest = currentStorage.latestActivity;
			if (!latest || typeof latest !== "object" || Array.isArray(latest)) return null;
			const activity = latest as AetherJsonObject;
			const payload = activity.payload && typeof activity.payload === "object" ? activity.payload as AetherJsonObject : {};
			const type = String(activity.type ?? "");
			const title = type === "web-search-error" ? "网页访问出错" : type === "web-search-content-ready" ? "网页内容已就绪" : "最近的网页活动";
			return aether.ui.column([
				statusCard(aether, title, { ...payload, text: activity.text }, type === "web-search-error" ? "error" : "neutral"),
				aether.ui.button("关闭", "dismiss-latest-activity", { tone: "neutral", icon: "close" }),
			], { spacing: 6 });
		},
	});

	return () => {
		const bridge = (globalThis as Record<PropertyKey, unknown>)[BRIDGE_KEY] as WebAccessBridge | undefined;
		if (bridge?.api === aether) delete (globalThis as Record<PropertyKey, unknown>)[BRIDGE_KEY];
	};
};

window.__ModuleLoader__.load({ id: "dsh-plugin-pocket", factory: (require) => {
var module = { exports: {} }; var exports = module.exports;
//#region rolldown:runtime
var __create = Object.create;
var __defProp = Object.defineProperty;
var __getOwnPropDesc = Object.getOwnPropertyDescriptor;
var __getOwnPropNames = Object.getOwnPropertyNames;
var __getProtoOf = Object.getPrototypeOf;
var __hasOwnProp = Object.prototype.hasOwnProperty;
var __copyProps = (to, from, except, desc) => {
	if (from && typeof from === "object" || typeof from === "function") for (var keys = __getOwnPropNames(from), i = 0, n = keys.length, key; i < n; i++) {
		key = keys[i];
		if (!__hasOwnProp.call(to, key) && key !== except) __defProp(to, key, {
			get: ((k) => from[k]).bind(null, key),
			enumerable: !(desc = __getOwnPropDesc(from, key)) || desc.enumerable
		});
	}
	return to;
};
var __toESM = (mod, isNodeMode, target) => (target = mod != null ? __create(__getProtoOf(mod)) : {}, __copyProps(isNodeMode || !mod || !mod.__esModule ? __defProp(target, "default", {
	value: mod,
	enumerable: true
}) : target, mod));

//#endregion
let react_jsx_runtime = require("react/jsx-runtime");
react_jsx_runtime = __toESM(react_jsx_runtime);
let react = require("react");
react = __toESM(react);
let react_dom = require("react-dom");
react_dom = __toESM(react_dom);

//#region src/client/api.ts
async function readBody(res) {
	if (typeof res?.json === "function") try {
		return await res.json();
	} catch {}
	if (typeof res?.text === "function") try {
		return await res.text();
	} catch {}
}
async function parse(res) {
	if (!res || res.ok !== true) {
		const body = await readBody(res);
		const detail = body === void 0 ? `HTTP ${res?.status ?? "?"}` : typeof body === "string" ? body : JSON.stringify(body);
		throw new Error(detail || `HTTP ${res?.status ?? "?"}`);
	}
	return await readBody(res);
}
/** Same-origin fetch helpers for the Host management routes. */
function createPocketApi(base = "") {
	const get = async (path, init) => parse(await fetch(`${base}${path}`, init));
	const networkPath = (path, network) => network ? `${path}?network=${network}` : path;
	return {
		status: (network) => get(networkPath("/pocket/status", network)),
		pairStatus: (network) => get(networkPath("/pocket/pair", network)),
		openPair: (network) => get(networkPath("/pocket/pair", network), { method: "POST" }),
		closePair: () => get("/pocket/pair", { method: "DELETE" }),
		listDevices: () => get("/pocket/devices").then((r) => r.devices),
		revoke: (id) => get("/pocket/devices", {
			method: "POST",
			headers: { "content-type": "application/json" },
			body: JSON.stringify({ id })
		}),
		rename: (id, name) => get("/pocket/devices", {
			method: "PATCH",
			headers: { "content-type": "application/json" },
			body: JSON.stringify({
				id,
				name
			})
		}),
		diagnostics: () => get("/pocket/diagnostics")
	};
}

//#endregion
//#region src/client/locales.ts
const zh = {
	entry: "手机连接",
	title: "手机连接",
	statusRunning: "服务就绪",
	statusStopped: "服务未就绪",
	newDevice: "连接新设备",
	network: "连接网络",
	lan: "局域网",
	tailscale: "Tailscale",
	lanAddress: "局域网地址",
	tailscaleAddress: "Tailscale 地址",
	noNetwork: "没有可用的网络地址",
	pair: "开始配对",
	pairAgain: "重新配对",
	pairClose: "关闭配对",
	code: "配对码",
	expiresIn: "{n} 秒后过期",
	copy: "复制配对链接",
	copyAddress: "复制地址",
	copied: "已复制",
	devices: "已配对设备",
	empty: "暂无已配对设备",
	online: "在线",
	offline: "离线",
	lastSeen: "上次在线",
	neverSeen: "尚未连接",
	rename: "重命名",
	renameSave: "保存",
	renameCancel: "取消",
	revoke: "解除配对",
	revokeTitle: "解除配对",
	revokeBody: "解除与「{name}」的配对？该手机需重新配对才能连接。",
	cancel: "取消",
	loading: "加载中…",
	refresh: "刷新",
	close: "关闭",
	diagnostics: "连接诊断",
	diagnosticsCopy: "复制诊断报告"
};
const en = {
	entry: "Phone connection",
	title: "Phone connection",
	statusRunning: "Service ready",
	statusStopped: "Service unavailable",
	newDevice: "Connect a device",
	network: "Connection network",
	lan: "LAN",
	tailscale: "Tailscale",
	lanAddress: "LAN address",
	tailscaleAddress: "Tailscale address",
	noNetwork: "No network address available",
	pair: "Start pairing",
	pairAgain: "Pair again",
	pairClose: "Close pairing",
	code: "Pairing code",
	expiresIn: "Expires in {n}s",
	copy: "Copy pairing link",
	copyAddress: "Copy address",
	copied: "Copied",
	devices: "Paired devices",
	empty: "No paired devices",
	online: "Online",
	offline: "Offline",
	lastSeen: "Last online",
	neverSeen: "Never connected",
	rename: "Rename",
	renameSave: "Save",
	renameCancel: "Cancel",
	revoke: "Unpair",
	revokeTitle: "Unpair device",
	revokeBody: "Unpair “{name}”? The phone will need to pair again to reconnect.",
	cancel: "Cancel",
	loading: "Loading…",
	refresh: "Refresh",
	close: "Close",
	diagnostics: "Connection diagnostics",
	diagnosticsCopy: "Copy diagnostic report"
};

//#endregion
//#region src/client/PhoneIcon.tsx
/** Matches dsh ui-primitives Medium outline icons: 16×16 viewBox, 1.3 stroke. */
const ICON_MEDIUM_STROKE = 1.3;
/** Markup used when swapping the shell's default gear in the nav cell. */
function phoneIconSvgHtml(className) {
	return `<svg width="16" height="16" class="${className}" viewBox="0 0 16 16" fill="none" xmlns="http://www.w3.org/2000/svg" aria-hidden="true" stroke-width="${ICON_MEDIUM_STROKE}" data-pocket-icon="1"><rect x="4.75" y="1.75" width="6.5" height="12.5" rx="1.25" stroke="currentColor"></rect><path d="M7 12.25h2" stroke="currentColor" stroke-linecap="round"></path></svg>`;
}

//#endregion
//#region src/client/nav-icon.ts
const LABELS = new Set(["手机连接", "Phone connection"]);
/**
* Settings nav icons are hardcoded by section id in
* `@deepseek-ai/dsh-client-ui-settings-general` (`navIcon`): unknown ids fall
* back to the gear. `settings.section` has no `icon` register option, and the
* primitives icon set has no phone glyph. Swap the gear SVG on our nav cell
* for a Medium-outline smartphone that matches shipped stroke/size.
*/
function installPocketNavIcon() {
	const sync = () => {
		for (const cell of document.querySelectorAll("[class*=\"navCell\"]")) {
			const label = (cell.innerText || "").trim();
			if (!LABELS.has(label)) continue;
			const svg = cell.querySelector("svg");
			if (!svg || svg.getAttribute("data-pocket-icon") === "1") continue;
			const cls = svg.getAttribute("class") || "";
			const wrap = document.createElement("span");
			wrap.innerHTML = phoneIconSvgHtml(cls);
			const next = wrap.firstElementChild;
			if (next) svg.replaceWith(next);
		}
	};
	const mo = new MutationObserver(() => sync());
	mo.observe(document.documentElement, {
		childList: true,
		subtree: true,
		characterData: true
	});
	sync();
	return () => mo.disconnect();
}

//#endregion
//#region src/client/styles.ts
const text = "var(--dsw-alias-label-primary)";
const muted = "var(--dsw-alias-label-secondary)";
const border = "1px solid var(--dsw-alias-border-l2)";
const surface = "var(--dsw-alias-bg-module-platform, var(--dsw-alias-bg-base))";
const button = {
	appearance: "none",
	display: "inline-flex",
	alignItems: "center",
	justifyContent: "center",
	gap: 8,
	border: "none",
	borderRadius: 8,
	background: "transparent",
	color: muted,
	cursor: "pointer",
	font: "inherit",
	fontSize: 13,
	lineHeight: "20px",
	padding: "8px 10px"
};
const styles = {
	settingsRoot: {
		width: "100%",
		padding: "8px 0 24px",
		color: text,
		containerType: "inline-size"
	},
	header: {
		display: "flex",
		alignItems: "center",
		justifyContent: "space-between",
		gap: 16,
		marginBottom: 24
	},
	title: {
		margin: 0,
		fontSize: 24,
		fontWeight: 650,
		lineHeight: "32px"
	},
	service: {
		display: "inline-flex",
		alignItems: "center",
		gap: 6,
		fontSize: 12,
		color: muted
	},
	pairingCard: {
		border,
		borderRadius: 16,
		background: surface,
		padding: "24px 24px 0"
	},
	pairingTop: {
		display: "flex",
		alignItems: "center",
		justifyContent: "space-between",
		gap: 24,
		minHeight: 168,
		paddingBottom: 24
	},
	pairControls: {
		display: "flex",
		flexDirection: "column",
		alignItems: "flex-start",
		gap: 18,
		minWidth: 0
	},
	rowTitle: {
		margin: 0,
		fontSize: 16,
		lineHeight: "24px",
		fontWeight: 600,
		color: text
	},
	illustration: {
		width: 180,
		maxWidth: "30%",
		flexShrink: 0,
		color: "var(--dsw-alias-label-tertiary)",
		opacity: .55,
		marginRight: 20
	},
	segments: {
		display: "inline-flex",
		padding: 3,
		borderRadius: 10,
		gap: 2,
		background: "var(--dsw-alias-bg-overlay)"
	},
	segment: {
		...button,
		minWidth: 88,
		padding: "6px 16px"
	},
	segmentActive: {
		color: text,
		background: "var(--dsw-alias-bg-base)",
		boxShadow: "0 1px 4px rgba(0,0,0,.08)"
	},
	footer: {
		display: "flex",
		flexWrap: "wrap",
		alignItems: "center",
		justifyContent: "space-between",
		gap: "8px 16px",
		borderTop: border,
		padding: "14px 0"
	},
	address: {
		display: "flex",
		flexWrap: "wrap",
		alignItems: "center",
		gap: 8,
		minWidth: 0,
		fontSize: 12,
		color: muted
	},
	addressValue: {
		fontFamily: "ui-monospace, SFMono-Regular, Menlo, Consolas, monospace",
		fontSize: 12,
		color: text,
		overflowWrap: "anywhere"
	},
	footerActions: {
		display: "flex",
		alignItems: "center",
		gap: 10,
		marginLeft: "auto"
	},
	divider: {
		width: 1,
		height: 16,
		background: "var(--dsw-alias-border-l2)",
		flexShrink: 0
	},
	iconBtn: {
		...button,
		padding: 8
	},
	textBtn: button,
	primaryBtn: {
		...button,
		height: 40,
		padding: "0 18px",
		fontSize: 14,
		fontWeight: 550,
		borderRadius: 10,
		background: "var(--dsw-alias-button-primary-fill)",
		color: "var(--dsw-alias-label-primary-foreground, var(--dsw-alias-bg-base))"
	},
	outlineBtn: {
		...button,
		border,
		color: text
	},
	outlineBtnSm: {
		...button,
		padding: "5px 8px",
		color: text
	},
	destructiveBtn: {
		...button,
		background: "var(--dsw-alias-state-error-primary)",
		color: "#fff",
		padding: "8px 14px"
	},
	ghostBtn: {
		...button,
		border,
		color: text
	},
	hint: {
		margin: 0,
		fontSize: 12,
		lineHeight: "20px",
		color: muted
	},
	errorText: {
		margin: "0 0 16px",
		fontSize: 12,
		lineHeight: "20px",
		color: "var(--dsw-alias-state-error-primary)"
	},
	stateDotSlot: (state) => ({
		display: "inline-flex",
		alignItems: "center",
		justifyContent: "center",
		width: 10,
		height: 10,
		flexShrink: 0,
		color: state === "done" ? "var(--dsw-alias-state-success-primary)" : state === "error" ? "var(--dsw-alias-state-error-primary)" : "var(--dsw-alias-label-tertiary)"
	}),
	stateDotCore: {
		width: 6,
		height: 6,
		borderRadius: "50%",
		background: "currentColor"
	},
	pairingContent: {
		display: "flex",
		flexWrap: "wrap",
		alignItems: "center",
		gap: 24,
		padding: "0 0 24px"
	},
	code: {
		fontFamily: "ui-monospace, SFMono-Regular, Menlo, Consolas, monospace",
		fontSize: 28,
		letterSpacing: ".16em",
		fontWeight: 600,
		margin: "4px 0 8px"
	},
	qrBox: {
		padding: 10,
		borderRadius: 10,
		background: "#fff",
		lineHeight: 0
	},
	actionRow: {
		display: "flex",
		flexWrap: "wrap",
		gap: 8,
		marginTop: 16
	},
	devicesSection: { marginTop: 30 },
	devicesHeader: {
		display: "flex",
		alignItems: "center",
		gap: 10,
		marginBottom: 16
	},
	count: {
		background: surface,
		color: muted,
		borderRadius: 8,
		minWidth: 24,
		textAlign: "center",
		fontSize: 12,
		padding: "2px 4px"
	},
	deviceList: {
		display: "flex",
		flexDirection: "column",
		gap: 10
	},
	deviceRow: {
		display: "flex",
		flexWrap: "wrap",
		alignItems: "center",
		gap: 14,
		padding: "18px 20px",
		border,
		borderRadius: 14
	},
	deviceIcon: {
		display: "flex",
		alignItems: "center",
		justifyContent: "center",
		width: 44,
		height: 48,
		borderRadius: 11,
		background: surface,
		color: muted
	},
	deviceInfo: {
		flex: "1 1 120px",
		minWidth: 0
	},
	deviceHeading: {
		display: "flex",
		alignItems: "center",
		gap: 10,
		minWidth: 0
	},
	deviceName: {
		fontSize: 14,
		lineHeight: "22px",
		fontWeight: 550,
		color: text,
		overflow: "hidden",
		textOverflow: "ellipsis",
		whiteSpace: "nowrap"
	},
	deviceMeta: {
		marginTop: 4,
		fontSize: 12,
		lineHeight: "18px",
		color: muted
	},
	deviceActions: {
		display: "flex",
		alignItems: "center",
		gap: 6,
		marginLeft: "auto"
	},
	input: {
		boxSizing: "border-box",
		width: "100%",
		minWidth: 0,
		height: 34,
		border,
		borderRadius: 8,
		padding: "0 10px",
		background: "var(--dsw-alias-bg-base)",
		color: text,
		font: "inherit",
		fontSize: 13
	},
	dialogMask: {
		position: "fixed",
		inset: 0,
		zIndex: 1100,
		display: "flex",
		alignItems: "center",
		justifyContent: "center",
		pointerEvents: "auto",
		background: "rgba(0,0,0,.45)",
		padding: 24
	},
	dialogCard: {
		width: "min(560px, calc(100vw - 48px))",
		maxHeight: "80vh",
		overflow: "auto",
		borderRadius: 14,
		border,
		background: "var(--dsw-alias-bg-base)",
		color: text,
		boxShadow: "0 12px 40px rgba(0,0,0,.2)",
		padding: 24,
		display: "flex",
		flexDirection: "column",
		gap: 12
	},
	dialogTitle: {
		margin: 0,
		fontSize: 16,
		fontWeight: 600,
		lineHeight: "24px"
	},
	dialogBody: {
		margin: 0,
		fontSize: 14,
		lineHeight: "22px",
		color: muted
	},
	dialogActions: {
		display: "flex",
		justifyContent: "flex-end",
		gap: 8,
		marginTop: 8
	},
	report: {
		margin: 0,
		whiteSpace: "pre-wrap",
		overflowWrap: "anywhere",
		userSelect: "text",
		WebkitUserSelect: "text",
		fontSize: 12,
		lineHeight: "20px",
		color: muted
	}
};
const panelCss = `
[data-pocket-panel] button:disabled { opacity: .45; cursor: not-allowed !important; }
[data-pocket-panel] button:not(:disabled):hover { filter: brightness(.94); }
[data-pocket-panel] button:focus-visible { outline: 2px solid var(--dsw-alias-label-secondary); outline-offset: 3px; }
@container (max-width: 440px) {
  [data-pocket-panel] .pocket-illustration { display: none; }
  [data-pocket-panel] .pocket-device-actions { width: 100%; justify-content: flex-end; }
}
`;

//#endregion
//#region src/client/ConfirmDialog.tsx
/**
* Lightweight confirm dialog matching dsh Modal / SignOutDialog chrome
* (mask + card + title + body + action row), built with theme tokens only.
* Does not `require` `@deepseek-ai/dsh-client-ui-primitives` (plugin hard rule).
*/
function ConfirmDialog({ open, title, body, cancelLabel, confirmLabel, busy = false, onCancel, onConfirm }) {
	(0, react.useEffect)(() => {
		if (!open) return;
		const onKey = (e) => {
			if (e.key === "Escape" && !busy) onCancel();
		};
		document.addEventListener("keydown", onKey);
		return () => document.removeEventListener("keydown", onKey);
	}, [
		open,
		busy,
		onCancel
	]);
	if (!open || typeof document === "undefined") return null;
	return (0, react_dom.createPortal)(/* @__PURE__ */ (0, react_jsx_runtime.jsx)("div", {
		style: styles.dialogMask,
		role: "presentation",
		onClick: (e) => {
			if (e.target === e.currentTarget && !busy) onCancel();
		},
		children: /* @__PURE__ */ (0, react_jsx_runtime.jsxs)("div", {
			role: "dialog",
			"aria-modal": "true",
			"aria-label": title,
			style: styles.dialogCard,
			onClick: (e) => e.stopPropagation(),
			children: [
				/* @__PURE__ */ (0, react_jsx_runtime.jsx)("h2", {
					style: styles.dialogTitle,
					children: title
				}),
				/* @__PURE__ */ (0, react_jsx_runtime.jsx)("div", {
					style: styles.dialogBody,
					children: body
				}),
				/* @__PURE__ */ (0, react_jsx_runtime.jsxs)("div", {
					style: styles.dialogActions,
					children: [/* @__PURE__ */ (0, react_jsx_runtime.jsx)("button", {
						type: "button",
						style: styles.ghostBtn,
						disabled: busy,
						"data-modal-autofocus": true,
						onClick: onCancel,
						children: cancelLabel
					}), /* @__PURE__ */ (0, react_jsx_runtime.jsx)("button", {
						type: "button",
						style: styles.destructiveBtn,
						disabled: busy,
						onClick: onConfirm,
						children: confirmLabel
					})]
				})
			]
		})
	}), document.body);
}

//#endregion
//#region node_modules/uqr/dist/index.mjs
var QrCodeDataType = /* @__PURE__ */ ((QrCodeDataType2) => {
	QrCodeDataType2[QrCodeDataType2["Border"] = -1] = "Border";
	QrCodeDataType2[QrCodeDataType2["Data"] = 0] = "Data";
	QrCodeDataType2[QrCodeDataType2["Function"] = 1] = "Function";
	QrCodeDataType2[QrCodeDataType2["Position"] = 2] = "Position";
	QrCodeDataType2[QrCodeDataType2["Timing"] = 3] = "Timing";
	QrCodeDataType2[QrCodeDataType2["Alignment"] = 4] = "Alignment";
	return QrCodeDataType2;
})(QrCodeDataType || {});
const LOW = [0, 1];
const MEDIUM = [1, 0];
const QUARTILE = [2, 3];
const HIGH = [3, 2];
const EccMap = {
	L: LOW,
	M: MEDIUM,
	Q: QUARTILE,
	H: HIGH
};
const NUMERIC_REGEX = /^\d*$/;
const ALPHANUMERIC_REGEX = /^[A-Z0-9 $%*+./:-]*$/;
const ALPHANUMERIC_CHARSET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:";
const MIN_VERSION = 1;
const MAX_VERSION = 40;
const PENALTY_N1 = 3;
const PENALTY_N2 = 3;
const PENALTY_N3 = 40;
const PENALTY_N4 = 10;
const ECC_CODEWORDS_PER_BLOCK = [
	[
		-1,
		7,
		10,
		15,
		20,
		26,
		18,
		20,
		24,
		30,
		18,
		20,
		24,
		26,
		30,
		22,
		24,
		28,
		30,
		28,
		28,
		28,
		28,
		30,
		30,
		26,
		28,
		30,
		30,
		30,
		30,
		30,
		30,
		30,
		30,
		30,
		30,
		30,
		30,
		30,
		30
	],
	[
		-1,
		10,
		16,
		26,
		18,
		24,
		16,
		18,
		22,
		22,
		26,
		30,
		22,
		22,
		24,
		24,
		28,
		28,
		26,
		26,
		26,
		26,
		28,
		28,
		28,
		28,
		28,
		28,
		28,
		28,
		28,
		28,
		28,
		28,
		28,
		28,
		28,
		28,
		28,
		28,
		28
	],
	[
		-1,
		13,
		22,
		18,
		26,
		18,
		24,
		18,
		22,
		20,
		24,
		28,
		26,
		24,
		20,
		30,
		24,
		28,
		28,
		26,
		30,
		28,
		30,
		30,
		30,
		30,
		28,
		30,
		30,
		30,
		30,
		30,
		30,
		30,
		30,
		30,
		30,
		30,
		30,
		30,
		30
	],
	[
		-1,
		17,
		28,
		22,
		16,
		22,
		28,
		26,
		26,
		24,
		28,
		24,
		28,
		22,
		24,
		24,
		30,
		28,
		28,
		26,
		28,
		30,
		24,
		30,
		30,
		30,
		30,
		30,
		30,
		30,
		30,
		30,
		30,
		30,
		30,
		30,
		30,
		30,
		30,
		30,
		30
	]
];
const NUM_ERROR_CORRECTION_BLOCKS = [
	[
		-1,
		1,
		1,
		1,
		1,
		1,
		2,
		2,
		2,
		2,
		4,
		4,
		4,
		4,
		4,
		6,
		6,
		6,
		6,
		7,
		8,
		8,
		9,
		9,
		10,
		12,
		12,
		12,
		13,
		14,
		15,
		16,
		17,
		18,
		19,
		19,
		20,
		21,
		22,
		24,
		25
	],
	[
		-1,
		1,
		1,
		1,
		2,
		2,
		4,
		4,
		4,
		5,
		5,
		5,
		8,
		9,
		9,
		10,
		10,
		11,
		13,
		14,
		16,
		17,
		17,
		18,
		20,
		21,
		23,
		25,
		26,
		28,
		29,
		31,
		33,
		35,
		37,
		38,
		40,
		43,
		45,
		47,
		49
	],
	[
		-1,
		1,
		1,
		2,
		2,
		4,
		4,
		6,
		6,
		8,
		8,
		8,
		10,
		12,
		16,
		12,
		17,
		16,
		18,
		21,
		20,
		23,
		23,
		25,
		27,
		29,
		34,
		34,
		35,
		38,
		40,
		43,
		45,
		48,
		51,
		53,
		56,
		59,
		62,
		65,
		68
	],
	[
		-1,
		1,
		1,
		2,
		4,
		4,
		4,
		5,
		6,
		8,
		8,
		11,
		11,
		16,
		16,
		18,
		16,
		19,
		21,
		25,
		25,
		25,
		34,
		30,
		32,
		35,
		37,
		40,
		42,
		45,
		48,
		51,
		54,
		57,
		60,
		63,
		66,
		70,
		74,
		77,
		81
	]
];
var QrCode = class {
	constructor(version, ecc, dataCodewords, msk) {
		this.version = version;
		this.ecc = ecc;
		if (version < MIN_VERSION || version > MAX_VERSION) throw new RangeError("Version value out of range");
		if (msk < -1 || msk > 7) throw new RangeError("Mask value out of range");
		this.size = version * 4 + 17;
		const row = Array.from({ length: this.size }).fill(false);
		for (let i = 0; i < this.size; i++) {
			this.modules.push(row.slice());
			this.types.push(row.map(() => 0));
		}
		this.drawFunctionPatterns();
		const allCodewords = this.addEccAndInterleave(dataCodewords);
		this.drawCodewords(allCodewords);
		if (msk === -1) {
			let minPenalty = 1e9;
			for (let i = 0; i < 8; i++) {
				this.applyMask(i);
				this.drawFormatBits(i);
				const penalty = this.getPenaltyScore();
				if (penalty < minPenalty) {
					msk = i;
					minPenalty = penalty;
				}
				this.applyMask(i);
			}
		}
		this.mask = msk;
		this.applyMask(msk);
		this.drawFormatBits(msk);
	}
	size;
	mask;
	modules = [];
	types = [];
	getModule(x, y) {
		return x >= 0 && x < this.size && y >= 0 && y < this.size && this.modules[y][x];
	}
	drawFunctionPatterns() {
		for (let i = 0; i < this.size; i++) {
			this.setFunctionModule(6, i, i % 2 === 0, QrCodeDataType.Timing);
			this.setFunctionModule(i, 6, i % 2 === 0, QrCodeDataType.Timing);
		}
		this.drawFinderPattern(3, 3);
		this.drawFinderPattern(this.size - 4, 3);
		this.drawFinderPattern(3, this.size - 4);
		const alignPatPos = this.getAlignmentPatternPositions();
		const numAlign = alignPatPos.length;
		for (let i = 0; i < numAlign; i++) for (let j = 0; j < numAlign; j++) if (!(i === 0 && j === 0 || i === 0 && j === numAlign - 1 || i === numAlign - 1 && j === 0)) this.drawAlignmentPattern(alignPatPos[i], alignPatPos[j]);
		this.drawFormatBits(0);
		this.drawVersion();
	}
	drawFormatBits(mask) {
		const data = this.ecc[1] << 3 | mask;
		let rem = data;
		for (let i = 0; i < 10; i++) rem = rem << 1 ^ (rem >>> 9) * 1335;
		const bits = (data << 10 | rem) ^ 21522;
		for (let i = 0; i <= 5; i++) this.setFunctionModule(8, i, getBit(bits, i));
		this.setFunctionModule(8, 7, getBit(bits, 6));
		this.setFunctionModule(8, 8, getBit(bits, 7));
		this.setFunctionModule(7, 8, getBit(bits, 8));
		for (let i = 9; i < 15; i++) this.setFunctionModule(14 - i, 8, getBit(bits, i));
		for (let i = 0; i < 8; i++) this.setFunctionModule(this.size - 1 - i, 8, getBit(bits, i));
		for (let i = 8; i < 15; i++) this.setFunctionModule(8, this.size - 15 + i, getBit(bits, i));
		this.setFunctionModule(8, this.size - 8, true);
	}
	drawVersion() {
		if (this.version < 7) return;
		let rem = this.version;
		for (let i = 0; i < 12; i++) rem = rem << 1 ^ (rem >>> 11) * 7973;
		const bits = this.version << 12 | rem;
		for (let i = 0; i < 18; i++) {
			const color = getBit(bits, i);
			const a = this.size - 11 + i % 3;
			const b = Math.floor(i / 3);
			this.setFunctionModule(a, b, color);
			this.setFunctionModule(b, a, color);
		}
	}
	drawFinderPattern(x, y) {
		for (let dy = -4; dy <= 4; dy++) for (let dx = -4; dx <= 4; dx++) {
			const dist = Math.max(Math.abs(dx), Math.abs(dy));
			const xx = x + dx;
			const yy = y + dy;
			if (xx >= 0 && xx < this.size && yy >= 0 && yy < this.size) this.setFunctionModule(xx, yy, dist !== 2 && dist !== 4, QrCodeDataType.Position);
		}
	}
	drawAlignmentPattern(x, y) {
		for (let dy = -2; dy <= 2; dy++) for (let dx = -2; dx <= 2; dx++) this.setFunctionModule(x + dx, y + dy, Math.max(Math.abs(dx), Math.abs(dy)) !== 1, QrCodeDataType.Alignment);
	}
	setFunctionModule(x, y, isDark, type = QrCodeDataType.Function) {
		this.modules[y][x] = isDark;
		this.types[y][x] = type;
	}
	addEccAndInterleave(data) {
		const ver = this.version;
		const ecl = this.ecc;
		if (data.length !== getNumDataCodewords(ver, ecl)) throw new RangeError("Invalid argument");
		const numBlocks = NUM_ERROR_CORRECTION_BLOCKS[ecl[0]][ver];
		const blockEccLen = ECC_CODEWORDS_PER_BLOCK[ecl[0]][ver];
		const rawCodewords = Math.floor(getNumRawDataModules(ver) / 8);
		const numShortBlocks = numBlocks - rawCodewords % numBlocks;
		const shortBlockLen = Math.floor(rawCodewords / numBlocks);
		const blocks = [];
		const rsDiv = reedSolomonComputeDivisor(blockEccLen);
		for (let i = 0, k = 0; i < numBlocks; i++) {
			const dat = data.slice(k, k + shortBlockLen - blockEccLen + (i < numShortBlocks ? 0 : 1));
			k += dat.length;
			const ecc = reedSolomonComputeRemainder(dat, rsDiv);
			if (i < numShortBlocks) dat.push(0);
			blocks.push(dat.concat(ecc));
		}
		const result = [];
		for (let i = 0; i < blocks[0].length; i++) blocks.forEach((block, j) => {
			if (i !== shortBlockLen - blockEccLen || j >= numShortBlocks) result.push(block[i]);
		});
		return result;
	}
	drawCodewords(data) {
		if (data.length !== Math.floor(getNumRawDataModules(this.version) / 8)) throw new RangeError("Invalid argument");
		let i = 0;
		for (let right = this.size - 1; right >= 1; right -= 2) {
			if (right === 6) right = 5;
			for (let vert = 0; vert < this.size; vert++) for (let j = 0; j < 2; j++) {
				const x = right - j;
				const y = (right + 1 & 2) === 0 ? this.size - 1 - vert : vert;
				if (!this.types[y][x] && i < data.length * 8) {
					this.modules[y][x] = getBit(data[i >>> 3], 7 - (i & 7));
					i++;
				}
			}
		}
	}
	applyMask(mask) {
		if (mask < 0 || mask > 7) throw new RangeError("Mask value out of range");
		for (let y = 0; y < this.size; y++) for (let x = 0; x < this.size; x++) {
			let invert;
			switch (mask) {
				case 0:
					invert = (x + y) % 2 === 0;
					break;
				case 1:
					invert = y % 2 === 0;
					break;
				case 2:
					invert = x % 3 === 0;
					break;
				case 3:
					invert = (x + y) % 3 === 0;
					break;
				case 4:
					invert = (Math.floor(x / 3) + Math.floor(y / 2)) % 2 === 0;
					break;
				case 5:
					invert = x * y % 2 + x * y % 3 === 0;
					break;
				case 6:
					invert = (x * y % 2 + x * y % 3) % 2 === 0;
					break;
				case 7:
					invert = ((x + y) % 2 + x * y % 3) % 2 === 0;
					break;
				default: throw new Error("Unreachable");
			}
			if (!this.types[y][x] && invert) this.modules[y][x] = !this.modules[y][x];
		}
	}
	getPenaltyScore() {
		let result = 0;
		for (let y = 0; y < this.size; y++) {
			let runColor = false;
			let runX = 0;
			const runHistory = [
				0,
				0,
				0,
				0,
				0,
				0,
				0
			];
			for (let x = 0; x < this.size; x++) if (this.modules[y][x] === runColor) {
				runX++;
				if (runX === 5) result += PENALTY_N1;
				else if (runX > 5) result++;
			} else {
				this.finderPenaltyAddHistory(runX, runHistory);
				if (!runColor) result += this.finderPenaltyCountPatterns(runHistory) * PENALTY_N3;
				runColor = this.modules[y][x];
				runX = 1;
			}
			result += this.finderPenaltyTerminateAndCount(runColor, runX, runHistory) * PENALTY_N3;
		}
		for (let x = 0; x < this.size; x++) {
			let runColor = false;
			let runY = 0;
			const runHistory = [
				0,
				0,
				0,
				0,
				0,
				0,
				0
			];
			for (let y = 0; y < this.size; y++) if (this.modules[y][x] === runColor) {
				runY++;
				if (runY === 5) result += PENALTY_N1;
				else if (runY > 5) result++;
			} else {
				this.finderPenaltyAddHistory(runY, runHistory);
				if (!runColor) result += this.finderPenaltyCountPatterns(runHistory) * PENALTY_N3;
				runColor = this.modules[y][x];
				runY = 1;
			}
			result += this.finderPenaltyTerminateAndCount(runColor, runY, runHistory) * PENALTY_N3;
		}
		for (let y = 0; y < this.size - 1; y++) for (let x = 0; x < this.size - 1; x++) {
			const color = this.modules[y][x];
			if (color === this.modules[y][x + 1] && color === this.modules[y + 1][x] && color === this.modules[y + 1][x + 1]) result += PENALTY_N2;
		}
		let dark = 0;
		for (const row of this.modules) dark = row.reduce((sum, color) => sum + (color ? 1 : 0), dark);
		const total = this.size * this.size;
		const k = Math.ceil(Math.abs(dark * 20 - total * 10) / total) - 1;
		result += k * PENALTY_N4;
		return result;
	}
	getAlignmentPatternPositions() {
		if (this.version === 1) return [];
		else {
			const numAlign = Math.floor(this.version / 7) + 2;
			const step = this.version === 32 ? 26 : Math.ceil((this.version * 4 + 4) / (numAlign * 2 - 2)) * 2;
			const result = [6];
			for (let pos = this.size - 7; result.length < numAlign; pos -= step) result.splice(1, 0, pos);
			return result;
		}
	}
	finderPenaltyCountPatterns(runHistory) {
		const n = runHistory[1];
		const core = n > 0 && runHistory[2] === n && runHistory[3] === n * 3 && runHistory[4] === n && runHistory[5] === n;
		return (core && runHistory[0] >= n * 4 && runHistory[6] >= n ? 1 : 0) + (core && runHistory[6] >= n * 4 && runHistory[0] >= n ? 1 : 0);
	}
	finderPenaltyTerminateAndCount(currentRunColor, currentRunLength, runHistory) {
		if (currentRunColor) {
			this.finderPenaltyAddHistory(currentRunLength, runHistory);
			currentRunLength = 0;
		}
		currentRunLength += this.size;
		this.finderPenaltyAddHistory(currentRunLength, runHistory);
		return this.finderPenaltyCountPatterns(runHistory);
	}
	finderPenaltyAddHistory(currentRunLength, runHistory) {
		if (runHistory[0] === 0) currentRunLength += this.size;
		runHistory.pop();
		runHistory.unshift(currentRunLength);
	}
};
function appendBits(val, len, bb) {
	if (len < 0 || len > 31 || val >>> len !== 0) throw new RangeError("Value out of range");
	for (let i = len - 1; i >= 0; i--) bb.push(val >>> i & 1);
}
function getBit(x, i) {
	return (x >>> i & 1) !== 0;
}
var QrSegment = class {
	constructor(mode, numChars, bitData) {
		this.mode = mode;
		this.numChars = numChars;
		this.bitData = bitData;
		if (numChars < 0) throw new RangeError("Invalid argument");
		this.bitData = bitData.slice();
	}
	getData() {
		return this.bitData.slice();
	}
};
const MODE_NUMERIC = [
	1,
	10,
	12,
	14
];
const MODE_ALPHANUMERIC = [
	2,
	9,
	11,
	13
];
const MODE_BYTE = [
	4,
	8,
	16,
	16
];
function numCharCountBits(mode, ver) {
	return mode[Math.floor((ver + 7) / 17) + 1];
}
function makeBytes(data) {
	const bb = [];
	for (const b of data) appendBits(b, 8, bb);
	return new QrSegment(MODE_BYTE, data.length, bb);
}
function makeNumeric(digits) {
	if (!isNumeric(digits)) throw new RangeError("String contains non-numeric characters");
	const bb = [];
	for (let i = 0; i < digits.length;) {
		const n = Math.min(digits.length - i, 3);
		appendBits(Number.parseInt(digits.substring(i, i + n), 10), n * 3 + 1, bb);
		i += n;
	}
	return new QrSegment(MODE_NUMERIC, digits.length, bb);
}
function makeAlphanumeric(text$1) {
	if (!isAlphanumeric(text$1)) throw new RangeError("String contains unencodable characters in alphanumeric mode");
	const bb = [];
	let i;
	for (i = 0; i + 2 <= text$1.length; i += 2) {
		let temp = ALPHANUMERIC_CHARSET.indexOf(text$1.charAt(i)) * 45;
		temp += ALPHANUMERIC_CHARSET.indexOf(text$1.charAt(i + 1));
		appendBits(temp, 11, bb);
	}
	if (i < text$1.length) appendBits(ALPHANUMERIC_CHARSET.indexOf(text$1.charAt(i)), 6, bb);
	return new QrSegment(MODE_ALPHANUMERIC, text$1.length, bb);
}
function makeSegments(text$1) {
	if (text$1 === "") return [];
	else if (isNumeric(text$1)) return [makeNumeric(text$1)];
	else if (isAlphanumeric(text$1)) return [makeAlphanumeric(text$1)];
	else return [makeBytes(toUtf8ByteArray(text$1))];
}
function isNumeric(text$1) {
	return NUMERIC_REGEX.test(text$1);
}
function isAlphanumeric(text$1) {
	return ALPHANUMERIC_REGEX.test(text$1);
}
function getTotalBits(segs, version) {
	let result = 0;
	for (const seg of segs) {
		const ccbits = numCharCountBits(seg.mode, version);
		if (seg.numChars >= 1 << ccbits) return Number.POSITIVE_INFINITY;
		result += 4 + ccbits + seg.bitData.length;
	}
	return result;
}
function toUtf8ByteArray(str) {
	str = encodeURI(str);
	const result = [];
	for (let i = 0; i < str.length; i++) if (str.charAt(i) !== "%") result.push(str.charCodeAt(i));
	else {
		result.push(Number.parseInt(str.substring(i + 1, i + 3), 16));
		i += 2;
	}
	return result;
}
function getNumRawDataModules(ver) {
	if (ver < MIN_VERSION || ver > MAX_VERSION) throw new RangeError("Version number out of range");
	let result = (16 * ver + 128) * ver + 64;
	if (ver >= 2) {
		const numAlign = Math.floor(ver / 7) + 2;
		result -= (25 * numAlign - 10) * numAlign - 55;
		if (ver >= 7) result -= 36;
	}
	return result;
}
function getNumDataCodewords(ver, ecl) {
	return Math.floor(getNumRawDataModules(ver) / 8) - ECC_CODEWORDS_PER_BLOCK[ecl[0]][ver] * NUM_ERROR_CORRECTION_BLOCKS[ecl[0]][ver];
}
function reedSolomonComputeDivisor(degree) {
	if (degree < 1 || degree > 255) throw new RangeError("Degree out of range");
	const result = [];
	for (let i = 0; i < degree - 1; i++) result.push(0);
	result.push(1);
	let root = 1;
	for (let i = 0; i < degree; i++) {
		for (let j = 0; j < result.length; j++) {
			result[j] = reedSolomonMultiply(result[j], root);
			if (j + 1 < result.length) result[j] ^= result[j + 1];
		}
		root = reedSolomonMultiply(root, 2);
	}
	return result;
}
function reedSolomonComputeRemainder(data, divisor) {
	const result = divisor.map((_) => 0);
	for (const b of data) {
		const factor = b ^ result.shift();
		result.push(0);
		divisor.forEach((coef, i) => result[i] ^= reedSolomonMultiply(coef, factor));
	}
	return result;
}
function reedSolomonMultiply(x, y) {
	if (x >>> 8 !== 0 || y >>> 8 !== 0) throw new RangeError("Byte out of range");
	let z = 0;
	for (let i = 7; i >= 0; i--) {
		z = z << 1 ^ (z >>> 7) * 285;
		z ^= (y >>> i & 1) * x;
	}
	return z;
}
function encodeSegments(segs, ecl, minVersion = 1, maxVersion = 40, mask = -1, boostEcl = true) {
	if (!(MIN_VERSION <= minVersion && minVersion <= maxVersion && maxVersion <= MAX_VERSION) || mask < -1 || mask > 7) throw new RangeError("Invalid value");
	let version;
	let dataUsedBits;
	for (version = minVersion;; version++) {
		const dataCapacityBits2 = getNumDataCodewords(version, ecl) * 8;
		const usedBits = getTotalBits(segs, version);
		if (usedBits <= dataCapacityBits2) {
			dataUsedBits = usedBits;
			break;
		}
		if (version >= maxVersion) throw new RangeError("Data too long");
	}
	for (const newEcl of [
		MEDIUM,
		QUARTILE,
		HIGH
	]) if (boostEcl && dataUsedBits <= getNumDataCodewords(version, newEcl) * 8) ecl = newEcl;
	const bb = [];
	for (const seg of segs) {
		appendBits(seg.mode[0], 4, bb);
		appendBits(seg.numChars, numCharCountBits(seg.mode, version), bb);
		for (const b of seg.getData()) bb.push(b);
	}
	const dataCapacityBits = getNumDataCodewords(version, ecl) * 8;
	appendBits(0, Math.min(4, dataCapacityBits - bb.length), bb);
	appendBits(0, (8 - bb.length % 8) % 8, bb);
	for (let padByte = 236; bb.length < dataCapacityBits; padByte ^= 253) appendBits(padByte, 8, bb);
	const dataCodewords = Array.from({ length: Math.ceil(bb.length / 8) }, () => 0);
	bb.forEach((b, i) => dataCodewords[i >>> 3] |= b << 7 - (i & 7));
	return new QrCode(version, ecl, dataCodewords, mask);
}
function encode(data, options) {
	const { ecc = "L", boostEcc = false, minVersion = 1, maxVersion = 40, maskPattern = -1, border: border$1 = 1 } = options || {};
	const segment = typeof data === "string" ? makeSegments(data) : Array.isArray(data) ? [makeBytes(data)] : void 0;
	if (!segment) throw new Error(`uqr only supports encoding string and binary data, but got: ${typeof data}`);
	const qr = encodeSegments(segment, EccMap[ecc], minVersion, maxVersion, maskPattern, boostEcc);
	const result = addBorder({
		version: qr.version,
		maskPattern: qr.mask,
		size: qr.size,
		data: qr.modules,
		types: qr.types
	}, border$1);
	if (options?.invert) result.data = result.data.map((row) => row.map((mod) => !mod));
	options?.onEncoded?.(result);
	return result;
}
function addBorder(input, border$1 = 1) {
	if (!border$1) return input;
	const { size } = input;
	const newSize = size + border$1 * 2;
	input.size = newSize;
	input.data.forEach((row) => {
		for (let i = 0; i < border$1; i++) {
			row.unshift(false);
			row.push(false);
		}
	});
	for (let i = 0; i < border$1; i++) {
		input.data.unshift(Array.from({ length: newSize }, (_) => false));
		input.data.push(Array.from({ length: newSize }, (_) => false));
	}
	const b = QrCodeDataType.Border;
	input.types.forEach((row) => {
		for (let i = 0; i < border$1; i++) {
			row.unshift(b);
			row.push(b);
		}
	});
	for (let i = 0; i < border$1; i++) {
		input.types.unshift(Array.from({ length: newSize }, (_) => b));
		input.types.push(Array.from({ length: newSize }, (_) => b));
	}
	return input;
}
function escapeAttr(value) {
	return value.replace(/&/g, "&amp;").replace(/"/g, "&quot;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}
function renderSVG(data, options = {}) {
	const result = encode(data, options);
	const { pixelSize = 10, whiteColor = "white", blackColor = "black" } = options;
	const height = result.size * pixelSize;
	const width = result.size * pixelSize;
	let svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${width} ${height}">`;
	const paths = [];
	for (let row = 0; row < result.size; row++) for (let col = 0; col < result.size; col++) {
		const x = col * pixelSize;
		const y = row * pixelSize;
		if (result.data[row][col]) paths.push(`M${x},${y}h${pixelSize}v${pixelSize}h-${pixelSize}z`);
	}
	svg += `<rect fill="${escapeAttr(whiteColor)}" width="${width}" height="${height}"/>`;
	svg += `<path fill="${escapeAttr(blackColor)}" d="${paths.join("")}"/>`;
	svg += "</svg>";
	return svg;
}

//#endregion
//#region src/client/qr.ts
/** Render a pairing URI as an SVG QR string. */
function qrSvg(text$1, size = 180) {
	return renderSVG(text$1, {
		ecc: "M",
		pixelSize: 8,
		border: 2,
		blackColor: "#111",
		whiteColor: "#fff"
	}).replace("<svg ", `<svg width="${size}" height="${size}" `);
}

//#endregion
//#region src/client/ManagePanel.tsx
function Icon({ name, size = 18 }) {
	const paths = {
		phone: /* @__PURE__ */ (0, react_jsx_runtime.jsxs)(react_jsx_runtime.Fragment, { children: [/* @__PURE__ */ (0, react_jsx_runtime.jsx)("rect", {
			x: "6",
			y: "2",
			width: "12",
			height: "20",
			rx: "3"
		}), /* @__PURE__ */ (0, react_jsx_runtime.jsx)("path", { d: "M11 18h2" })] }),
		copy: /* @__PURE__ */ (0, react_jsx_runtime.jsxs)(react_jsx_runtime.Fragment, { children: [/* @__PURE__ */ (0, react_jsx_runtime.jsx)("rect", {
			x: "8",
			y: "8",
			width: "12",
			height: "14",
			rx: "2"
		}), /* @__PURE__ */ (0, react_jsx_runtime.jsx)("path", { d: "M15 4V2H4v14h1" })] }),
		refresh: /* @__PURE__ */ (0, react_jsx_runtime.jsx)(react_jsx_runtime.Fragment, { children: /* @__PURE__ */ (0, react_jsx_runtime.jsx)("path", { d: "M20 7a9 9 0 1 0 0 10M20 2v5h-5" }) }),
		plus: /* @__PURE__ */ (0, react_jsx_runtime.jsx)("path", { d: "M12 4v16M4 12h16" }),
		check: /* @__PURE__ */ (0, react_jsx_runtime.jsx)("path", { d: "m5 12 4 4L19 6" })
	};
	return /* @__PURE__ */ (0, react_jsx_runtime.jsx)("svg", {
		width: size,
		height: size,
		viewBox: "0 0 24 24",
		fill: "none",
		stroke: "currentColor",
		strokeWidth: "1.6",
		strokeLinecap: "round",
		strokeLinejoin: "round",
		"aria-hidden": "true",
		children: paths[name]
	});
}
function StatusDot({ state }) {
	return /* @__PURE__ */ (0, react_jsx_runtime.jsx)("span", {
		style: styles.stateDotSlot(state),
		"aria-hidden": "true",
		children: /* @__PURE__ */ (0, react_jsx_runtime.jsx)("span", { style: styles.stateDotCore })
	});
}
function DeviceItem({ device, t, api, onChanged, onError }) {
	const [editing, setEditing] = (0, react.useState)(false);
	const [name, setName] = (0, react.useState)(device.name);
	const [confirmOpen, setConfirmOpen] = (0, react.useState)(false);
	const [busy, setBusy] = (0, react.useState)(false);
	(0, react.useEffect)(() => {
		setName(device.name);
	}, [device.name]);
	const save = async () => {
		const trimmed = name.trim();
		if (!trimmed || trimmed === device.name) {
			setEditing(false);
			return;
		}
		setBusy(true);
		try {
			onChanged((await api.rename(device.id, trimmed)).devices);
			setEditing(false);
		} catch (e) {
			onError(String(e?.message ?? e));
		} finally {
			setBusy(false);
		}
	};
	const revoke = async () => {
		setBusy(true);
		try {
			onChanged((await api.revoke(device.id)).devices);
			setConfirmOpen(false);
		} catch (e) {
			onError(String(e?.message ?? e));
		} finally {
			setBusy(false);
		}
	};
	const lastSeen = device.lastSeenAt == null ? t("neverSeen") : new Date(device.lastSeenAt).toLocaleString(void 0, {
		month: "2-digit",
		day: "2-digit",
		hour: "2-digit",
		minute: "2-digit",
		hour12: false
	});
	return /* @__PURE__ */ (0, react_jsx_runtime.jsxs)("div", {
		style: styles.deviceRow,
		children: [
			/* @__PURE__ */ (0, react_jsx_runtime.jsx)("div", {
				style: styles.deviceIcon,
				children: /* @__PURE__ */ (0, react_jsx_runtime.jsx)(Icon, {
					name: "phone",
					size: 28
				})
			}),
			/* @__PURE__ */ (0, react_jsx_runtime.jsxs)("div", {
				style: styles.deviceInfo,
				children: [editing ? /* @__PURE__ */ (0, react_jsx_runtime.jsx)("input", {
					style: styles.input,
					"aria-label": t("rename"),
					value: name,
					disabled: busy,
					onChange: (e) => setName(e.target.value),
					autoFocus: true,
					onKeyDown: (e) => {
						if (e.key === "Enter") save();
						if (e.key === "Escape") {
							setEditing(false);
							setName(device.name);
						}
					}
				}) : /* @__PURE__ */ (0, react_jsx_runtime.jsxs)("div", {
					style: styles.deviceHeading,
					children: [/* @__PURE__ */ (0, react_jsx_runtime.jsx)("span", {
						style: styles.deviceName,
						title: device.name,
						children: device.name
					}), /* @__PURE__ */ (0, react_jsx_runtime.jsxs)("span", {
						style: styles.service,
						children: [/* @__PURE__ */ (0, react_jsx_runtime.jsx)(StatusDot, { state: device.online ? "done" : "idle" }), device.online ? t("online") : t("offline")]
					})]
				}), /* @__PURE__ */ (0, react_jsx_runtime.jsxs)("div", {
					style: styles.deviceMeta,
					children: [
						t("lastSeen"),
						" ",
						lastSeen
					]
				})]
			}),
			/* @__PURE__ */ (0, react_jsx_runtime.jsx)("div", {
				className: "pocket-device-actions",
				style: styles.deviceActions,
				children: editing ? /* @__PURE__ */ (0, react_jsx_runtime.jsxs)(react_jsx_runtime.Fragment, { children: [/* @__PURE__ */ (0, react_jsx_runtime.jsx)("button", {
					type: "button",
					style: styles.outlineBtnSm,
					disabled: busy,
					onClick: () => void save(),
					children: t("renameSave")
				}), /* @__PURE__ */ (0, react_jsx_runtime.jsx)("button", {
					type: "button",
					style: styles.textBtn,
					disabled: busy,
					onClick: () => {
						setEditing(false);
						setName(device.name);
					},
					children: t("renameCancel")
				})] }) : /* @__PURE__ */ (0, react_jsx_runtime.jsxs)(react_jsx_runtime.Fragment, { children: [
					/* @__PURE__ */ (0, react_jsx_runtime.jsx)("button", {
						type: "button",
						style: styles.textBtn,
						disabled: busy,
						onClick: () => setEditing(true),
						children: t("rename")
					}),
					/* @__PURE__ */ (0, react_jsx_runtime.jsx)("span", { style: styles.divider }),
					/* @__PURE__ */ (0, react_jsx_runtime.jsx)("button", {
						type: "button",
						style: styles.textBtn,
						disabled: busy,
						onClick: () => setConfirmOpen(true),
						children: t("revoke")
					})
				] })
			}),
			/* @__PURE__ */ (0, react_jsx_runtime.jsx)(ConfirmDialog, {
				open: confirmOpen,
				title: t("revokeTitle"),
				body: t("revokeBody", { name: device.name }).replaceAll("{name}", device.name),
				cancelLabel: t("cancel"),
				confirmLabel: t("revoke"),
				busy,
				onCancel: () => {
					if (!busy) setConfirmOpen(false);
				},
				onConfirm: () => void revoke()
			})
		]
	});
}
function ManagePanel({ t, api }) {
	const [status, setStatus] = (0, react.useState)(null);
	const [error, setError] = (0, react.useState)(null);
	const [loading, setLoading] = (0, react.useState)(true);
	const [busy, setBusy] = (0, react.useState)(false);
	const [copied, setCopied] = (0, react.useState)(null);
	const [diagnostics, setDiagnostics] = (0, react.useState)(null);
	const [diagBusy, setDiagBusy] = (0, react.useState)(false);
	const network = (0, react.useRef)(void 0);
	const request = (0, react.useRef)(0);
	const copyTimer = (0, react.useRef)(void 0);
	const refresh = (0, react.useCallback)(async (background = false) => {
		const id = ++request.current;
		if (!background) setLoading(true);
		try {
			const s = await api.status(network.current);
			if (id !== request.current) return;
			setStatus(s);
			setError(null);
		} catch (e) {
			if (id !== request.current) return;
			setStatus(null);
			setError(String(e?.message ?? e));
		} finally {
			if (id === request.current) setLoading(false);
		}
	}, [api]);
	(0, react.useEffect)(() => {
		refresh();
		return () => {
			request.current++;
			clearTimeout(copyTimer.current);
		};
	}, [refresh]);
	(0, react.useEffect)(() => {
		if (!status?.pairing.open) return;
		const timer = setInterval(() => {
			refresh(true);
		}, 1e3);
		return () => clearInterval(timer);
	}, [status?.pairing.open, refresh]);
	(0, react.useEffect)(() => {
		if (!diagnostics) return;
		const onKey = (e) => {
			if (e.key === "Escape") setDiagnostics(null);
		};
		document.addEventListener("keydown", onKey);
		return () => document.removeEventListener("keydown", onKey);
	}, [diagnostics]);
	const changeNetwork = (next) => {
		network.current = next;
		setCopied(null);
		setStatus((s) => s ? {
			...s,
			pairing: { open: false }
		} : s);
		refresh();
	};
	const updatePairing = async (open) => {
		setBusy(true);
		setError(null);
		try {
			if (open) await api.openPair(network.current);
			else await api.closePair();
			await refresh();
		} catch (e) {
			setError(String(e?.message ?? e));
		} finally {
			setBusy(false);
		}
	};
	const copy = async (text$1, key) => {
		try {
			await navigator.clipboard.writeText(text$1);
			clearTimeout(copyTimer.current);
			setCopied(key);
			copyTimer.current = window.setTimeout(() => setCopied(null), 1500);
		} catch (e) {
			setError(String(e?.message ?? e));
		}
	};
	const diagnose = async () => {
		setDiagBusy(true);
		try {
			setDiagnostics(await api.diagnostics());
		} catch (e) {
			setError(String(e?.message ?? e));
		} finally {
			setDiagBusy(false);
		}
	};
	const pairing = status?.pairing;
	const selected = status?.connectionType;
	const pairReady = Boolean(status?.running && status.networks.length && status.host);
	const pairingOpen = Boolean(pairReady && pairing?.open && pairing.code && pairing.uri);
	const address = status?.host ? `${status.host}:${status.port}` : "";
	const disabled = loading || busy;
	return /* @__PURE__ */ (0, react_jsx_runtime.jsxs)("div", {
		"data-pocket-panel": true,
		style: styles.settingsRoot,
		children: [
			/* @__PURE__ */ (0, react_jsx_runtime.jsx)("style", { children: panelCss }),
			/* @__PURE__ */ (0, react_jsx_runtime.jsxs)("header", {
				style: styles.header,
				children: [/* @__PURE__ */ (0, react_jsx_runtime.jsx)("h2", {
					style: styles.title,
					children: t("title")
				}), /* @__PURE__ */ (0, react_jsx_runtime.jsxs)("span", {
					style: styles.service,
					children: [/* @__PURE__ */ (0, react_jsx_runtime.jsx)(StatusDot, { state: pairReady ? "done" : "idle" }), loading && !status ? t("loading") : t(pairReady ? "statusRunning" : "statusStopped")]
				})]
			}),
			error ? /* @__PURE__ */ (0, react_jsx_runtime.jsx)("div", {
				role: "alert",
				style: styles.errorText,
				children: error
			}) : null,
			/* @__PURE__ */ (0, react_jsx_runtime.jsxs)("section", {
				style: styles.pairingCard,
				"aria-label": t("newDevice"),
				children: [
					/* @__PURE__ */ (0, react_jsx_runtime.jsxs)("div", {
						style: {
							...styles.pairingTop,
							...pairingOpen ? { minHeight: 0 } : {}
						},
						children: [/* @__PURE__ */ (0, react_jsx_runtime.jsxs)("div", {
							style: styles.pairControls,
							children: [
								/* @__PURE__ */ (0, react_jsx_runtime.jsx)("h3", {
									style: styles.rowTitle,
									children: t("newDevice")
								}),
								status && status.networks.length > 1 ? /* @__PURE__ */ (0, react_jsx_runtime.jsx)("div", {
									style: styles.segments,
									role: "group",
									"aria-label": t("network"),
									children: status.networks.map((n) => /* @__PURE__ */ (0, react_jsx_runtime.jsx)("button", {
										type: "button",
										"aria-pressed": selected === n.type,
										style: {
											...styles.segment,
											...selected === n.type ? styles.segmentActive : {}
										},
										disabled,
										onClick: () => changeNetwork(n.type),
										children: t(n.type)
									}, n.type))
								}) : null,
								!pairingOpen ? /* @__PURE__ */ (0, react_jsx_runtime.jsxs)("button", {
									type: "button",
									style: styles.primaryBtn,
									disabled: disabled || !pairReady,
									onClick: () => void updatePairing(true),
									children: [/* @__PURE__ */ (0, react_jsx_runtime.jsx)(Icon, { name: "plus" }), t("pair")]
								}) : null
							]
						}), !pairingOpen ? /* @__PURE__ */ (0, react_jsx_runtime.jsxs)("svg", {
							className: "pocket-illustration",
							style: styles.illustration,
							viewBox: "0 0 180 130",
							fill: "none",
							stroke: "currentColor",
							strokeWidth: "2",
							strokeLinecap: "round",
							strokeLinejoin: "round",
							"aria-hidden": "true",
							children: [
								/* @__PURE__ */ (0, react_jsx_runtime.jsx)("path", { d: "M20 94V22a8 8 0 0 1 8-8h105a8 8 0 0 1 8 8v11M121 94H7v8h114" }),
								/* @__PURE__ */ (0, react_jsx_runtime.jsx)("rect", {
									x: "132",
									y: "43",
									width: "40",
									height: "75",
									rx: "8"
								}),
								/* @__PURE__ */ (0, react_jsx_runtime.jsx)("path", { d: "M148 50h9m-8 59h7" })
							]
						}) : null]
					}),
					pairingOpen ? /* @__PURE__ */ (0, react_jsx_runtime.jsxs)("div", {
						style: styles.pairingContent,
						children: [/* @__PURE__ */ (0, react_jsx_runtime.jsx)("div", {
							style: styles.qrBox,
							role: "img",
							"aria-label": t("code"),
							dangerouslySetInnerHTML: { __html: qrSvg(pairing.uri, 168) }
						}), /* @__PURE__ */ (0, react_jsx_runtime.jsxs)("div", { children: [
							/* @__PURE__ */ (0, react_jsx_runtime.jsx)("div", {
								style: styles.code,
								children: pairing.code
							}),
							/* @__PURE__ */ (0, react_jsx_runtime.jsx)("p", {
								style: styles.hint,
								children: t("expiresIn", { n: Math.max(0, pairing.expiresInSec ?? 0) })
							}),
							/* @__PURE__ */ (0, react_jsx_runtime.jsxs)("div", {
								style: styles.actionRow,
								children: [
									/* @__PURE__ */ (0, react_jsx_runtime.jsx)("button", {
										type: "button",
										style: styles.outlineBtn,
										onClick: () => void copy(pairing.pairingString || pairing.uri, "pair"),
										children: t(copied === "pair" ? "copied" : "copy")
									}),
									/* @__PURE__ */ (0, react_jsx_runtime.jsx)("button", {
										type: "button",
										style: styles.textBtn,
										disabled,
										onClick: () => void updatePairing(false),
										children: t("pairClose")
									}),
									/* @__PURE__ */ (0, react_jsx_runtime.jsx)("button", {
										type: "button",
										style: styles.textBtn,
										disabled,
										onClick: () => void updatePairing(true),
										children: t("pairAgain")
									})
								]
							})
						] })]
					}) : null,
					/* @__PURE__ */ (0, react_jsx_runtime.jsxs)("footer", {
						style: styles.footer,
						children: [/* @__PURE__ */ (0, react_jsx_runtime.jsx)("div", {
							style: styles.address,
							children: address ? /* @__PURE__ */ (0, react_jsx_runtime.jsxs)(react_jsx_runtime.Fragment, { children: [
								/* @__PURE__ */ (0, react_jsx_runtime.jsx)("span", { children: t(selected === "tailscale" ? "tailscaleAddress" : "lanAddress") }),
								/* @__PURE__ */ (0, react_jsx_runtime.jsx)("code", {
									style: styles.addressValue,
									children: address
								}),
								/* @__PURE__ */ (0, react_jsx_runtime.jsx)("button", {
									type: "button",
									style: styles.iconBtn,
									title: t(copied === "address" ? "copied" : "copyAddress"),
									"aria-label": t(copied === "address" ? "copied" : "copyAddress"),
									onClick: () => void copy(address, "address"),
									children: /* @__PURE__ */ (0, react_jsx_runtime.jsx)(Icon, {
										name: copied === "address" ? "check" : "copy",
										size: 16
									})
								})
							] }) : /* @__PURE__ */ (0, react_jsx_runtime.jsx)("span", { children: t(loading ? "loading" : "noNetwork") })
						}), /* @__PURE__ */ (0, react_jsx_runtime.jsxs)("div", {
							style: styles.footerActions,
							children: [
								/* @__PURE__ */ (0, react_jsx_runtime.jsx)("button", {
									type: "button",
									style: styles.iconBtn,
									disabled,
									title: t("refresh"),
									"aria-label": t("refresh"),
									onClick: () => {
										if (!status) network.current = void 0;
										refresh();
									},
									children: /* @__PURE__ */ (0, react_jsx_runtime.jsx)(Icon, { name: "refresh" })
								}),
								/* @__PURE__ */ (0, react_jsx_runtime.jsx)("span", { style: styles.divider }),
								/* @__PURE__ */ (0, react_jsx_runtime.jsx)("button", {
									type: "button",
									style: styles.textBtn,
									disabled: diagBusy,
									onClick: () => void diagnose(),
									children: t("diagnostics")
								})
							]
						})]
					})
				]
			}),
			/* @__PURE__ */ (0, react_jsx_runtime.jsxs)("section", {
				style: styles.devicesSection,
				"aria-label": t("devices"),
				children: [/* @__PURE__ */ (0, react_jsx_runtime.jsxs)("div", {
					style: styles.devicesHeader,
					children: [/* @__PURE__ */ (0, react_jsx_runtime.jsx)("h3", {
						style: styles.rowTitle,
						children: t("devices")
					}), /* @__PURE__ */ (0, react_jsx_runtime.jsx)("span", {
						style: styles.count,
						children: status?.devices.length ?? 0
					})]
				}), /* @__PURE__ */ (0, react_jsx_runtime.jsx)("div", {
					style: styles.deviceList,
					children: status?.devices.length ? status.devices.map((device) => /* @__PURE__ */ (0, react_jsx_runtime.jsx)(DeviceItem, {
						device,
						t,
						api,
						onError: setError,
						onChanged: (devices) => setStatus((s) => s ? {
							...s,
							devices
						} : s)
					}, device.id)) : /* @__PURE__ */ (0, react_jsx_runtime.jsx)("p", {
						style: styles.hint,
						children: t(loading ? "loading" : "empty")
					})
				})]
			}),
			diagnostics ? /* @__PURE__ */ (0, react_jsx_runtime.jsx)("div", {
				style: styles.dialogMask,
				onClick: (e) => {
					if (e.target === e.currentTarget) setDiagnostics(null);
				},
				children: /* @__PURE__ */ (0, react_jsx_runtime.jsxs)("div", {
					role: "dialog",
					"aria-modal": "true",
					"aria-label": t("diagnostics"),
					style: styles.dialogCard,
					children: [
						/* @__PURE__ */ (0, react_jsx_runtime.jsx)("h3", {
							style: styles.dialogTitle,
							children: t("diagnostics")
						}),
						/* @__PURE__ */ (0, react_jsx_runtime.jsx)("pre", {
							style: styles.report,
							children: diagnostics.report
						}),
						/* @__PURE__ */ (0, react_jsx_runtime.jsxs)("div", {
							style: styles.dialogActions,
							children: [/* @__PURE__ */ (0, react_jsx_runtime.jsx)("button", {
								type: "button",
								style: styles.outlineBtn,
								onClick: () => void copy(diagnostics.report, "diagnostics"),
								children: t(copied === "diagnostics" ? "copied" : "diagnosticsCopy")
							}), /* @__PURE__ */ (0, react_jsx_runtime.jsx)("button", {
								type: "button",
								style: styles.primaryBtn,
								autoFocus: true,
								onClick: () => setDiagnostics(null),
								children: t("close")
							})]
						})
					]
				})
			}) : null
		]
	});
}

//#endregion
//#region src/client/PocketSettings.tsx
function PocketSettings({ t, api }) {
	return /* @__PURE__ */ (0, react_jsx_runtime.jsx)(ManagePanel, {
		t,
		api
	});
}

//#endregion
//#region src/client/host-ui-styles.ts
/** Scoped to this WebView window; no generated classes or plugin-specific selectors. */
const css = `
html[data-pocket-ui] [data-pocket-ui-notice] {
  position: fixed;
  z-index: 1100;
  inset: auto 12px max(12px, env(safe-area-inset-bottom));
  box-sizing: border-box;
  max-height: 50dvh;
  overflow: auto;
  padding: 16px;
  border: 1px solid var(--dsw-alias-border-l2);
  border-radius: var(--dsw-radius-panel, 16px);
  background: var(--dsw-alias-bg-layer-2);
  color: var(--dsw-alias-label-primary);
  box-shadow: var(--dsw-elevation-prominent);
  font: inherit;
  overflow-wrap: anywhere;
}
html[data-pocket-ui] [data-pocket-ui-notice] p { margin: 0 0 12px; }
html[data-pocket-ui] [data-pocket-ui-notice] > div { display: flex; flex-wrap: wrap; gap: 8px; }
html[data-pocket-ui] [data-pocket-ui-notice] button {
  min-height: 44px;
  padding: 8px 12px;
  border: 1px solid var(--dsw-alias-border-l2);
  border-radius: var(--dsw-radius-md, 12px);
  background: var(--dsw-alias-bg-base);
  color: inherit;
  font: inherit;
  cursor: pointer;
}
@media (max-width: 700px) {
  html[data-pocket-ui] [data-shortcut-modal="settings"] {
    box-sizing: border-box;
    flex-direction: column;
    width: 100%;
    max-width: 100%;
    height: 100dvh;
    max-height: 100dvh;
    padding: env(safe-area-inset-top) env(safe-area-inset-right) env(safe-area-inset-bottom) env(safe-area-inset-left);
    border-radius: 0;
  }
  html[data-pocket-ui] [data-shortcut-modal="settings"] > nav {
    width: 100%;
    min-width: 0;
    padding: 12px 12px 0;
    gap: 8px;
    border-bottom: 1px solid var(--dsw-alias-border-l2);
  }
  html[data-pocket-ui] [data-shortcut-modal="settings"] > nav > div:last-child {
    flex-direction: row;
    overflow-x: auto;
    overflow-y: hidden;
    padding-bottom: 8px;
    overscroll-behavior-x: contain;
  }
  html[data-pocket-ui] [data-shortcut-modal="settings"] > nav button {
    flex: 0 0 auto;
    min-height: 44px;
    white-space: nowrap;
  }
  html[data-pocket-ui] [data-shortcut-modal="settings"] > nav + div {
    min-width: 0;
    min-height: 0;
    width: 100%;
  }
  html[data-pocket-ui] [data-shortcut-modal="settings"] > nav + div > div:last-child {
    min-width: 0;
    min-height: 0;
    padding-left: 16px;
    padding-right: 16px;
    overflow: auto;
    overscroll-behavior: contain;
  }
}
`;
function installHostUiStyles() {
	const root = document.documentElement;
	const previous = root.getAttribute("data-pocket-ui");
	const style = document.createElement("style");
	style.dataset.pocketUiStyles = "";
	style.textContent = css;
	root.setAttribute("data-pocket-ui", "");
	document.head.append(style);
	return () => {
		style.remove();
		if (previous === null) root.removeAttribute("data-pocket-ui");
		else root.setAttribute("data-pocket-ui", previous);
	};
}

//#endregion
//#region src/client/HostUiLauncher.tsx
const NS$1 = "pocket.host-ui";
const messages = {
	zh: {
		waiting: "请求的设置页“{section}”尚未注册。插件就绪后将自动打开；也可以浏览当前已注册的设置。",
		unsupported: "此 Host 的设置导航接口不兼容，无法直接打开“{section}”。请从设置导航中选择页面。",
		settings: "浏览已注册的设置",
		dismiss: "返回完整界面"
	},
	en: {
		waiting: "The requested settings section “{section}” is not registered yet. It will open when its plugin is ready, or you can browse the currently registered settings.",
		unsupported: "This Host does not expose compatible settings navigation for “{section}”. Please select the page from Settings.",
		settings: "Browse registered settings",
		dismiss: "Return to the full interface"
	}
};
/**
* The shell publishes its store on the settings.launcher-owning sidebar entry.
* Its create() returns the SAME root-scoped instance used by the renderer and
* keyboard command (ui-settings-general), not a second private React tree.
*/
function sectionNavigator(slots) {
	const store = slots.entries("sidebar.settings").find((entry) => entry.children?.["settings.launcher"])?.store;
	if (typeof store !== "object" || store === null || !("create" in store) || typeof store.create !== "function") return void 0;
	const instance = store.create();
	if (typeof instance?.actions?.openSection !== "function") return void 0;
	return (id) => {
		instance.actions.openSection(id);
	};
}
/** A temporary launcher occupant; the official launcher resumes after navigation. */
function HostUiLauncher({ section, slots, finish, openSettings, t, useSessionReadiness }) {
	const ready = useSessionReadiness((state) => state.phase === "ready");
	const [problem, setProblem] = (0, react.useState)("waiting");
	(0, react.useEffect)(() => {
		let finished = false;
		const navigate = () => {
			if (!ready) return;
			if (finished) return;
			if (!slots.entries("settings.section").some((entry) => entry.options.id === section)) {
				setProblem("waiting");
				return;
			}
			const openSection = sectionNavigator(slots);
			if (!openSection) {
				setProblem("unsupported");
				return;
			}
			finished = true;
			queueMicrotask(() => {
				if (!finished) return;
				openSection(section);
				finish();
			});
		};
		const offSections = slots.subscribe("settings.section", navigate);
		const offShell = slots.subscribe("sidebar.settings", navigate);
		navigate();
		return () => {
			finished = false;
			offSections();
			offShell();
		};
	}, [
		section,
		slots,
		finish,
		ready
	]);
	return (0, react_dom.createPortal)(/* @__PURE__ */ (0, react_jsx_runtime.jsxs)("aside", {
		"data-pocket-ui-notice": "",
		role: "status",
		"aria-live": "polite",
		children: [/* @__PURE__ */ (0, react_jsx_runtime.jsx)("p", { children: t(problem).replace("{section}", () => section) }), /* @__PURE__ */ (0, react_jsx_runtime.jsxs)("div", { children: [/* @__PURE__ */ (0, react_jsx_runtime.jsx)("button", {
			type: "button",
			onClick: () => {
				finish();
				openSettings();
			},
			children: t("settings")
		}), /* @__PURE__ */ (0, react_jsx_runtime.jsx)("button", {
			type: "button",
			onClick: finish,
			children: t("dismiss")
		})] })]
	}), document.body);
}
/** Returns false on ordinary desktop windows, leaving all existing registrations intact. */
function installHostUi(ctx) {
	const query = new URLSearchParams(window.location.search);
	if (query.get("pocket-ui") !== "1") return false;
	ctx.effect(() => installHostUiStyles(), "pocket:host-ui-styles");
	const section = query.get("section");
	if (!section) return true;
	ctx.effect(() => ctx.locale.register(NS$1, messages), "pocket:host-ui-locale");
	let completed = false;
	ctx.inject(["sessions"], (scope) => {
		scope.slots.inject("settings.launcher", () => {
			if (completed) return;
			const finish = () => {
				completed = true;
				unregister();
			};
			const unregister = scope.slots.register({
				name: "settings.launcher",
				priority: -100,
				locale: NS$1,
				inject: () => ({
					section,
					slots: scope.slots,
					finish,
					hooks: { sessionReadiness: scope.sessions.list }
				})
			}, HostUiLauncher);
			return unregister;
		});
	});
	return true;
}

//#endregion
//#region src/client/index.ts
/** Must be a static array — cordis rejects function-form inject. */
const inject = ["slots", "locale"];
const NS = "pocket";
function apply(ctx) {
	if (installHostUi(ctx)) return;
	ctx.effect(() => ctx.locale.register(NS, {
		zh,
		en
	}), "pocket:locale");
	ctx.effect(() => installPocketNavIcon(), "pocket:nav-icon");
	const t = ctx.locale.bind(NS);
	const api = createPocketApi();
	ctx.slots.inject("settings.section", () => ctx.slots.register({
		name: "settings.section",
		id: "pocket",
		order: 55,
		label: () => t("entry"),
		locale: NS,
		inject: () => ({ api })
	}, PocketSettings));
}

//#endregion
exports.apply = apply;
exports.inject = inject;
return module.exports; } });
//# sourceMappingURL=client.js.map
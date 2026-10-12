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
	const url = (path) => `${base}${path}`;
	const get = async (path, init) => parse(await fetch(url(path), init));
	return {
		status: () => get("/pocket/status"),
		pairStatus: () => get("/pocket/pair"),
		openPair: () => get("/pocket/pair", { method: "POST" }),
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
		})
	};
}

//#endregion
//#region src/client/locales.ts
const zh = {
	entry: "手机连接",
	title: "手机连接",
	statusTitle: "连接器状态",
	statusRunning: "运行中",
	statusStopped: "未运行",
	listen: "监听地址",
	version: "版本",
	pair: "开始配对",
	pairAgain: "重新配对",
	pairClose: "关闭配对",
	pairingOpen: "配对窗口已打开",
	code: "配对码",
	expiresIn: "{n} 秒后过期",
	expiresMin: "约 {n} 分钟有效",
	scanHint: "用手机 App 扫描二维码，或手动输入配对码",
	uriHint: "配对链接",
	copy: "复制",
	copied: "已复制",
	devices: "已配对设备",
	empty: "还没有手机配对过",
	online: "在线",
	offline: "离线",
	lastSeen: "最近活跃",
	neverSeen: "尚未连接",
	rename: "重命名",
	renameSave: "保存",
	renameCancel: "取消",
	revoke: "撤销",
	revokeTitle: "撤销设备",
	revokeBody: "将撤销「{name}」。之后该手机需重新配对才能连接。",
	cancel: "取消",
	loading: "加载中…",
	loadFailed: "加载失败",
	refresh: "刷新",
	close: "关闭",
	noCode: "点击「开始配对」生成 6 位配对码"
};
const en = {
	entry: "Phone connection",
	title: "Phone connection",
	statusTitle: "Connector status",
	statusRunning: "Running",
	statusStopped: "Stopped",
	listen: "Listen address",
	version: "Version",
	pair: "Start pairing",
	pairAgain: "Pair again",
	pairClose: "Close pairing",
	pairingOpen: "Pairing window open",
	code: "Pairing code",
	expiresIn: "Expires in {n}s",
	expiresMin: "Valid for about {n} min",
	scanHint: "Scan the QR with the phone app, or enter the code",
	uriHint: "Pairing link",
	copy: "Copy",
	copied: "Copied",
	devices: "Paired devices",
	empty: "No phone paired yet",
	online: "Online",
	offline: "Offline",
	lastSeen: "Last seen",
	neverSeen: "Never connected",
	rename: "Rename",
	renameSave: "Save",
	renameCancel: "Cancel",
	revoke: "Revoke",
	revokeTitle: "Revoke device",
	revokeBody: "Revoke “{name}”. The phone will need to pair again to reconnect.",
	cancel: "Cancel",
	loading: "Loading…",
	loadFailed: "Failed to load",
	refresh: "Refresh",
	close: "Close",
	noCode: "Click “Start pairing” for a 6-digit code"
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
/**
* Inline styles aligned with dsh settings chrome / ui-primitives tokens.
* Does not `require` `@deepseek-ai/dsh-client-ui-primitives` (plugin hard rule);
* geometry mirrors SettingsForm fields, DeveloperToolsRow, Button, StateDot.
*/
const styles = {
	settingsRoot: {
		display: "flex",
		flexDirection: "column",
		width: "100%",
		padding: "0 0 24px"
	},
	settingsRow: {
		display: "flex",
		alignItems: "center",
		justifyContent: "space-between",
		gap: 24,
		padding: "16px 0",
		borderBottom: "0.5px solid var(--dsw-alias-border-l2)"
	},
	settingsBlock: {
		display: "flex",
		flexDirection: "column",
		gap: 0,
		padding: "16px 0",
		borderBottom: "0.5px solid var(--dsw-alias-border-l2)"
	},
	rowTitle: {
		fontSize: 14,
		lineHeight: "20px",
		color: "var(--dsw-alias-label-primary)"
	},
	rowDescription: {
		marginTop: 4,
		fontSize: 12,
		lineHeight: "18px",
		color: "var(--dsw-alias-label-secondary)"
	},
	hint: {
		margin: 0,
		fontSize: 12,
		lineHeight: "18px",
		color: "var(--dsw-alias-label-tertiary)"
	},
	errorText: {
		margin: "0 0 12px",
		fontSize: 12,
		lineHeight: "18px",
		color: "var(--dsw-alias-state-error-primary)"
	},
	valueLine: {
		display: "inline-flex",
		alignItems: "center",
		gap: 8,
		fontSize: 14,
		lineHeight: "20px",
		color: "var(--dsw-alias-label-primary)"
	},
	stateDotSlot: (state) => ({
		position: "relative",
		display: "inline-block",
		flex: "none",
		width: 10,
		height: 10,
		color: state === "done" ? "var(--dsw-alias-state-success-primary)" : state === "error" ? "var(--dsw-alias-state-error-primary)" : "var(--dsw-alias-state-idle-primary, var(--dsw-alias-label-tertiary))"
	}),
	stateDotCore: {
		position: "absolute",
		inset: "20%",
		borderRadius: "50%",
		background: "currentColor"
	},
	metaStack: {
		display: "flex",
		flexDirection: "column",
		gap: 4,
		marginTop: 10
	},
	metaLine: {
		fontSize: 12,
		lineHeight: "18px",
		color: "var(--dsw-alias-label-tertiary)"
	},
	primaryBtn: {
		appearance: "none",
		boxSizing: "border-box",
		display: "inline-flex",
		alignItems: "center",
		justifyContent: "center",
		height: 36,
		padding: "0 14px",
		border: "none",
		borderRadius: "var(--dsw-radius-md, 8px)",
		background: "var(--dsw-alias-button-primary-fill)",
		color: "var(--dsw-alias-label-primary-foreground, var(--dsw-alias-bg-base))",
		cursor: "pointer",
		font: "inherit",
		fontSize: 14,
		lineHeight: "22px"
	},
	outlineBtn: {
		appearance: "none",
		boxSizing: "border-box",
		display: "inline-flex",
		alignItems: "center",
		justifyContent: "center",
		height: 36,
		padding: "0 14px",
		border: "0.5px solid var(--dsw-alias-border-l3)",
		borderRadius: "var(--dsw-radius-md, 8px)",
		background: "transparent",
		color: "var(--dsw-alias-label-primary)",
		cursor: "pointer",
		font: "inherit",
		fontSize: 14,
		lineHeight: "22px"
	},
	outlineBtnSm: {
		appearance: "none",
		boxSizing: "border-box",
		display: "inline-flex",
		alignItems: "center",
		justifyContent: "center",
		height: 28,
		padding: "0 10px",
		border: "0.5px solid var(--dsw-alias-border-l3)",
		borderRadius: "var(--dsw-radius-sm, 6px)",
		background: "transparent",
		color: "var(--dsw-alias-label-primary)",
		cursor: "pointer",
		font: "inherit",
		fontSize: 12,
		lineHeight: "18px"
	},
	dangerBtn: {
		appearance: "none",
		boxSizing: "border-box",
		display: "inline-flex",
		alignItems: "center",
		justifyContent: "center",
		height: 28,
		padding: "0 10px",
		border: "none",
		borderRadius: "var(--dsw-radius-sm, 6px)",
		background: "transparent",
		color: "var(--dsw-alias-state-error-primary)",
		cursor: "pointer",
		font: "inherit",
		fontSize: 12,
		lineHeight: "18px"
	},
	destructiveBtn: {
		appearance: "none",
		boxSizing: "border-box",
		display: "inline-flex",
		alignItems: "center",
		justifyContent: "center",
		height: 36,
		padding: "0 14px",
		border: "none",
		borderRadius: "var(--dsw-radius-md, 8px)",
		background: "var(--dsw-alias-state-error-primary)",
		color: "var(--dsw-alias-label-primary-foreground, #fff)",
		cursor: "pointer",
		font: "inherit",
		fontSize: 14,
		lineHeight: "22px",
		fontWeight: 500
	},
	ghostBtn: {
		appearance: "none",
		boxSizing: "border-box",
		display: "inline-flex",
		alignItems: "center",
		justifyContent: "center",
		height: 36,
		padding: "0 14px",
		border: "0.5px solid var(--dsw-alias-border-l3)",
		borderRadius: "var(--dsw-radius-md, 8px)",
		background: "transparent",
		color: "var(--dsw-alias-label-primary)",
		cursor: "pointer",
		font: "inherit",
		fontSize: 14,
		lineHeight: "22px"
	},
	dialogMask: {
		position: "fixed",
		inset: 0,
		zIndex: 1100,
		display: "flex",
		alignItems: "center",
		justifyContent: "center",
		pointerEvents: "auto",
		background: "rgba(0, 0, 0, 0.45)",
		padding: 24
	},
	dialogCard: {
		width: "min(400px, calc(100vw - 48px))",
		borderRadius: "var(--dsw-radius-panel, 12px)",
		border: "0.5px solid var(--dsw-alias-border-l2)",
		background: "var(--dsw-alias-bg-base)",
		color: "var(--dsw-alias-label-primary)",
		boxShadow: "var(--dsw-elevation-prominent, 0 12px 40px rgba(0,0,0,0.25))",
		padding: "20px 22px 18px",
		display: "flex",
		flexDirection: "column",
		gap: 12
	},
	dialogTitle: {
		margin: 0,
		fontSize: 16,
		fontWeight: 500,
		lineHeight: "24px"
	},
	dialogBody: {
		margin: 0,
		fontSize: 14,
		lineHeight: "22px",
		color: "var(--dsw-alias-label-secondary)"
	},
	dialogActions: {
		display: "flex",
		justifyContent: "flex-end",
		gap: 8,
		marginTop: 8
	},
	code: {
		fontFamily: "ui-monospace, SFMono-Regular, Menlo, Consolas, monospace",
		fontSize: 28,
		letterSpacing: "0.16em",
		fontWeight: 600,
		textAlign: "center",
		color: "var(--dsw-alias-label-primary)",
		padding: "12px 0 4px"
	},
	qrWrap: {
		display: "flex",
		justifyContent: "center",
		padding: "12px 0 4px"
	},
	qrBox: {
		padding: 10,
		borderRadius: "var(--dsw-radius-md, 8px)",
		background: "#fff",
		border: "0.5px solid var(--dsw-alias-border-l3)",
		lineHeight: 0
	},
	actionRow: {
		display: "flex",
		flexWrap: "wrap",
		gap: 8,
		marginTop: 12
	},
	deviceRow: {
		display: "flex",
		alignItems: "center",
		gap: 12,
		padding: "16px 0",
		borderBottom: "0.5px solid var(--dsw-alias-border-l2)"
	},
	deviceName: {
		fontSize: 14,
		lineHeight: "20px",
		color: "var(--dsw-alias-label-primary)",
		overflow: "hidden",
		textOverflow: "ellipsis",
		whiteSpace: "nowrap"
	},
	deviceMeta: {
		marginTop: 4,
		fontSize: 12,
		lineHeight: "18px",
		color: "var(--dsw-alias-label-secondary)"
	},
	deviceActions: {
		display: "flex",
		alignItems: "center",
		gap: 8,
		flex: "none"
	},
	input: {
		boxSizing: "border-box",
		flex: 1,
		minWidth: 0,
		height: 34,
		border: "0.5px solid var(--dsw-alias-border-l4)",
		borderRadius: "var(--dsw-radius-md, 8px)",
		padding: "0 12px",
		background: "var(--dsw-alias-bg-layer-3, var(--dsw-alias-bg-base))",
		color: "var(--dsw-alias-label-primary)",
		font: "inherit",
		fontSize: 13,
		lineHeight: "1.5"
	}
};

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
function makeAlphanumeric(text) {
	if (!isAlphanumeric(text)) throw new RangeError("String contains unencodable characters in alphanumeric mode");
	const bb = [];
	let i;
	for (i = 0; i + 2 <= text.length; i += 2) {
		let temp = ALPHANUMERIC_CHARSET.indexOf(text.charAt(i)) * 45;
		temp += ALPHANUMERIC_CHARSET.indexOf(text.charAt(i + 1));
		appendBits(temp, 11, bb);
	}
	if (i < text.length) appendBits(ALPHANUMERIC_CHARSET.indexOf(text.charAt(i)), 6, bb);
	return new QrSegment(MODE_ALPHANUMERIC, text.length, bb);
}
function makeSegments(text) {
	if (text === "") return [];
	else if (isNumeric(text)) return [makeNumeric(text)];
	else if (isAlphanumeric(text)) return [makeAlphanumeric(text)];
	else return [makeBytes(toUtf8ByteArray(text))];
}
function isNumeric(text) {
	return NUMERIC_REGEX.test(text);
}
function isAlphanumeric(text) {
	return ALPHANUMERIC_REGEX.test(text);
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
	const { ecc = "L", boostEcc = false, minVersion = 1, maxVersion = 40, maskPattern = -1, border = 1 } = options || {};
	const segment = typeof data === "string" ? makeSegments(data) : Array.isArray(data) ? [makeBytes(data)] : void 0;
	if (!segment) throw new Error(`uqr only supports encoding string and binary data, but got: ${typeof data}`);
	const qr = encodeSegments(segment, EccMap[ecc], minVersion, maxVersion, maskPattern, boostEcc);
	const result = addBorder({
		version: qr.version,
		maskPattern: qr.mask,
		size: qr.size,
		data: qr.modules,
		types: qr.types
	}, border);
	if (options?.invert) result.data = result.data.map((row) => row.map((mod) => !mod));
	options?.onEncoded?.(result);
	return result;
}
function addBorder(input, border = 1) {
	if (!border) return input;
	const { size } = input;
	const newSize = size + border * 2;
	input.size = newSize;
	input.data.forEach((row) => {
		for (let i = 0; i < border; i++) {
			row.unshift(false);
			row.push(false);
		}
	});
	for (let i = 0; i < border; i++) {
		input.data.unshift(Array.from({ length: newSize }, (_) => false));
		input.data.push(Array.from({ length: newSize }, (_) => false));
	}
	const b = QrCodeDataType.Border;
	input.types.forEach((row) => {
		for (let i = 0; i < border; i++) {
			row.unshift(b);
			row.push(b);
		}
	});
	for (let i = 0; i < border; i++) {
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
function qrSvg(text, size = 180) {
	return renderSVG(text, {
		ecc: "M",
		pixelSize: 8,
		border: 2,
		blackColor: "#111",
		whiteColor: "#fff"
	}).replace("<svg ", `<svg width="${size}" height="${size}" `);
}

//#endregion
//#region src/client/ManagePanel.tsx
function formatLastSeen(t, at) {
	if (at == null) return t("neverSeen");
	try {
		return new Date(at).toLocaleString();
	} catch {
		return t("neverSeen");
	}
}
/** Prefer framework interpolation; fall back to `{name}` replacement. */
function tr(t, key, vars) {
	const out = t(key, vars);
	if (out !== key && !/\{name\}/.test(out)) return out;
	return Object.entries(vars).reduce((s, [k, v]) => s.replaceAll(`{${k}}`, String(v)), out === key ? String(zhFallback[key] ?? key) : out);
}
const zhFallback = { revokeBody: "将撤销「{name}」。之后该手机需重新配对才能连接。" };
/** Visual twin of primitives `StateDot` (10px slot / 6px core); no package require. */
function StatusDot({ state }) {
	return /* @__PURE__ */ (0, react_jsx_runtime.jsx)("span", {
		style: styles.stateDotSlot(state),
		"aria-hidden": "true",
		children: /* @__PURE__ */ (0, react_jsx_runtime.jsx)("span", { style: styles.stateDotCore })
	});
}
function DeviceItem({ device, t, api, onChanged }) {
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
		} finally {
			setBusy(false);
		}
	};
	const confirmRevoke = async () => {
		setBusy(true);
		try {
			onChanged((await api.revoke(device.id)).devices);
			setConfirmOpen(false);
		} finally {
			setBusy(false);
		}
	};
	return /* @__PURE__ */ (0, react_jsx_runtime.jsxs)("div", {
		style: styles.deviceRow,
		children: [
			/* @__PURE__ */ (0, react_jsx_runtime.jsx)(StatusDot, { state: device.online ? "done" : "idle" }),
			/* @__PURE__ */ (0, react_jsx_runtime.jsxs)("div", {
				style: {
					flex: 1,
					minWidth: 0
				},
				children: [editing ? /* @__PURE__ */ (0, react_jsx_runtime.jsx)("input", {
					style: styles.input,
					value: name,
					disabled: busy,
					onChange: (e) => setName(e.target.value),
					onKeyDown: (e) => {
						if (e.key === "Enter") save();
						if (e.key === "Escape") {
							setEditing(false);
							setName(device.name);
						}
					},
					autoFocus: true
				}) : /* @__PURE__ */ (0, react_jsx_runtime.jsx)("div", {
					style: styles.deviceName,
					children: device.name
				}), /* @__PURE__ */ (0, react_jsx_runtime.jsxs)("div", {
					style: styles.deviceMeta,
					children: [
						device.online ? t("online") : t("offline"),
						" · ",
						t("lastSeen"),
						": ",
						formatLastSeen(t, device.lastSeenAt)
					]
				})]
			}),
			/* @__PURE__ */ (0, react_jsx_runtime.jsx)("div", {
				style: styles.deviceActions,
				children: editing ? /* @__PURE__ */ (0, react_jsx_runtime.jsxs)(react_jsx_runtime.Fragment, { children: [/* @__PURE__ */ (0, react_jsx_runtime.jsx)("button", {
					type: "button",
					style: styles.outlineBtnSm,
					disabled: busy,
					onClick: () => void save(),
					children: t("renameSave")
				}), /* @__PURE__ */ (0, react_jsx_runtime.jsx)("button", {
					type: "button",
					style: styles.outlineBtnSm,
					disabled: busy,
					onClick: () => {
						setEditing(false);
						setName(device.name);
					},
					children: t("renameCancel")
				})] }) : /* @__PURE__ */ (0, react_jsx_runtime.jsxs)(react_jsx_runtime.Fragment, { children: [/* @__PURE__ */ (0, react_jsx_runtime.jsx)("button", {
					type: "button",
					style: styles.outlineBtnSm,
					disabled: busy,
					onClick: () => setEditing(true),
					children: t("rename")
				}), /* @__PURE__ */ (0, react_jsx_runtime.jsx)("button", {
					type: "button",
					style: styles.dangerBtn,
					disabled: busy,
					onClick: () => setConfirmOpen(true),
					children: t("revoke")
				})] })
			}),
			/* @__PURE__ */ (0, react_jsx_runtime.jsx)(ConfirmDialog, {
				open: confirmOpen,
				title: t("revokeTitle"),
				body: tr(t, "revokeBody", { name: device.name }),
				cancelLabel: t("cancel"),
				confirmLabel: t("revoke"),
				busy,
				onCancel: () => {
					if (!busy) setConfirmOpen(false);
				},
				onConfirm: () => void confirmRevoke()
			})
		]
	});
}
function ManagePanel({ t, api }) {
	const [status, setStatus] = (0, react.useState)(null);
	const [error, setError] = (0, react.useState)(null);
	const [loading, setLoading] = (0, react.useState)(true);
	const [copied, setCopied] = (0, react.useState)(false);
	const [tick, setTick] = (0, react.useState)(0);
	const refresh = (0, react.useCallback)(async () => {
		setError(null);
		try {
			setStatus(await api.status());
		} catch (e) {
			setError(String(e?.message ?? e));
		} finally {
			setLoading(false);
		}
	}, [api]);
	(0, react.useEffect)(() => {
		refresh();
	}, [refresh]);
	(0, react.useEffect)(() => {
		if (!status?.pairing.open) return;
		const id = setInterval(() => {
			setTick((n) => n + 1);
			refresh();
		}, 1e3);
		return () => clearInterval(id);
	}, [status?.pairing.open, refresh]);
	const openPair = async () => {
		setLoading(true);
		try {
			await api.openPair();
			await refresh();
		} catch (e) {
			setError(String(e?.message ?? e));
		} finally {
			setLoading(false);
		}
	};
	const closePair = async () => {
		try {
			await api.closePair();
			await refresh();
		} catch (e) {
			setError(String(e?.message ?? e));
		}
	};
	const copyUri = async () => {
		const uri = status?.pairing.uri;
		if (!uri) return;
		try {
			await navigator.clipboard.writeText(uri);
			setCopied(true);
			setTimeout(() => setCopied(false), 1500);
		} catch {}
	};
	const pairing = status?.pairing;
	const expires = pairing?.open ? Math.max(0, pairing.expiresInSec ?? 0) : 0;
	const connectorOk = Boolean(status?.running) && !error;
	return /* @__PURE__ */ (0, react_jsx_runtime.jsxs)("div", {
		style: styles.settingsRoot,
		children: [
			loading && !status ? /* @__PURE__ */ (0, react_jsx_runtime.jsx)("div", {
				style: styles.hint,
				children: t("loading")
			}) : null,
			error ? /* @__PURE__ */ (0, react_jsx_runtime.jsxs)("div", {
				style: styles.errorText,
				children: [
					t("loadFailed"),
					": ",
					error
				]
			}) : null,
			status ? /* @__PURE__ */ (0, react_jsx_runtime.jsxs)("div", {
				style: {
					...styles.settingsRow,
					alignItems: "flex-start"
				},
				children: [/* @__PURE__ */ (0, react_jsx_runtime.jsxs)("div", {
					style: {
						flex: 1,
						minWidth: 0
					},
					children: [
						/* @__PURE__ */ (0, react_jsx_runtime.jsx)("div", {
							style: styles.rowTitle,
							children: t("statusTitle")
						}),
						/* @__PURE__ */ (0, react_jsx_runtime.jsxs)("div", {
							style: {
								...styles.valueLine,
								marginTop: 6
							},
							children: [status.running ? t("statusRunning") : t("statusStopped"), /* @__PURE__ */ (0, react_jsx_runtime.jsx)(StatusDot, { state: connectorOk ? "done" : "error" })]
						}),
						/* @__PURE__ */ (0, react_jsx_runtime.jsxs)("div", {
							style: styles.metaStack,
							children: [/* @__PURE__ */ (0, react_jsx_runtime.jsxs)("div", {
								style: styles.metaLine,
								children: [
									t("listen"),
									": ",
									status.listen.map((l) => l.address).join(", ") || `${status.host}:${status.port}`
								]
							}), /* @__PURE__ */ (0, react_jsx_runtime.jsxs)("div", {
								style: styles.metaLine,
								children: [
									t("version"),
									": ",
									status.version
								]
							})]
						})
					]
				}), /* @__PURE__ */ (0, react_jsx_runtime.jsx)("button", {
					type: "button",
					style: styles.outlineBtnSm,
					onClick: () => void refresh(),
					children: t("refresh")
				})]
			}) : null,
			/* @__PURE__ */ (0, react_jsx_runtime.jsxs)("div", {
				style: styles.settingsBlock,
				children: [/* @__PURE__ */ (0, react_jsx_runtime.jsx)("div", {
					style: styles.rowTitle,
					children: t("code")
				}), pairing?.open && pairing.code ? /* @__PURE__ */ (0, react_jsx_runtime.jsxs)(react_jsx_runtime.Fragment, { children: [
					/* @__PURE__ */ (0, react_jsx_runtime.jsx)("div", {
						style: styles.code,
						children: pairing.code
					}),
					/* @__PURE__ */ (0, react_jsx_runtime.jsxs)("div", {
						style: {
							...styles.hint,
							textAlign: "center"
						},
						children: [
							t("expiresIn", { n: expires }),
							" · ",
							t("expiresMin", { n: Math.max(1, Math.round(expires / 60)) })
						]
					}),
					pairing.uri ? /* @__PURE__ */ (0, react_jsx_runtime.jsxs)(react_jsx_runtime.Fragment, { children: [
						/* @__PURE__ */ (0, react_jsx_runtime.jsx)("div", {
							style: styles.qrWrap,
							children: /* @__PURE__ */ (0, react_jsx_runtime.jsx)("div", {
								style: styles.qrBox,
								dangerouslySetInnerHTML: { __html: qrSvg(pairing.uri, 168) }
							})
						}),
						/* @__PURE__ */ (0, react_jsx_runtime.jsx)("div", {
							style: {
								...styles.rowDescription,
								textAlign: "center"
							},
							children: t("scanHint")
						}),
						/* @__PURE__ */ (0, react_jsx_runtime.jsxs)("div", {
							style: {
								...styles.actionRow,
								justifyContent: "center"
							},
							children: [
								/* @__PURE__ */ (0, react_jsx_runtime.jsx)("button", {
									type: "button",
									style: styles.outlineBtn,
									onClick: () => void copyUri(),
									children: copied ? t("copied") : t("copy")
								}),
								/* @__PURE__ */ (0, react_jsx_runtime.jsx)("button", {
									type: "button",
									style: styles.outlineBtn,
									onClick: () => void closePair(),
									children: t("pairClose")
								}),
								/* @__PURE__ */ (0, react_jsx_runtime.jsx)("button", {
									type: "button",
									style: styles.primaryBtn,
									onClick: () => void openPair(),
									children: t("pairAgain")
								})
							]
						})
					] }) : null
				] }) : /* @__PURE__ */ (0, react_jsx_runtime.jsxs)(react_jsx_runtime.Fragment, { children: [/* @__PURE__ */ (0, react_jsx_runtime.jsx)("div", {
					style: {
						...styles.rowDescription,
						marginTop: 4
					},
					children: t("noCode")
				}), /* @__PURE__ */ (0, react_jsx_runtime.jsx)("div", {
					style: styles.actionRow,
					children: /* @__PURE__ */ (0, react_jsx_runtime.jsx)("button", {
						type: "button",
						style: styles.primaryBtn,
						onClick: () => void openPair(),
						children: t("pair")
					})
				})] })]
			}),
			/* @__PURE__ */ (0, react_jsx_runtime.jsxs)("div", {
				style: styles.settingsBlock,
				children: [/* @__PURE__ */ (0, react_jsx_runtime.jsx)("div", {
					style: styles.rowTitle,
					children: t("devices")
				}), (status?.devices.length ?? 0) === 0 ? /* @__PURE__ */ (0, react_jsx_runtime.jsx)("div", {
					style: {
						...styles.rowDescription,
						marginTop: 8
					},
					children: t("empty")
				}) : /* @__PURE__ */ (0, react_jsx_runtime.jsx)("div", {
					style: { marginTop: 4 },
					children: status.devices.map((d) => /* @__PURE__ */ (0, react_jsx_runtime.jsx)(DeviceItem, {
						device: d,
						t,
						api,
						onChanged: (devices) => setStatus((s) => s ? {
							...s,
							devices
						} : s)
					}, d.id))
				})]
			})
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
//#region src/client/index.ts
/** Must be a static array — cordis rejects function-form inject. */
const inject = ["slots", "locale"];
const NS = "pocket";
function apply(ctx) {
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
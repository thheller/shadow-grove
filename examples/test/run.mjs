// drives the examples in headless chromium over CDP and checks what their text says
//
//   node examples/test/run.mjs                  all scenarios
//   node examples/test/run.mjs shop kv          only these
//   BASE=http://localhost:3020 CHROME=/usr/bin/chromium node examples/test/run.mjs
//
// needs the dev-http on :3020 (shadow-cljs.edn) and the :examples build compiled.
// scenarios live in examples/test/scenarios/<name>.mjs and export default async (t) => {...}
// any uncaught exception or console.error during a scenario fails it, unless the
// scenario expects it via t.expectConsoleError(/pattern/).

import { spawn } from "node:child_process";
import { mkdtempSync, readdirSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, dirname } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const BASE = process.env.BASE || "http://localhost:3020";
const chrome = process.env.CHROME || "/usr/bin/chromium";
const only = process.argv.slice(2);

const scenarioDir = join(here, "scenarios");
const names = readdirSync(scenarioDir)
  .filter((f) => f.endsWith(".mjs"))
  .map((f) => f.replace(/\.mjs$/, ""))
  .filter((n) => only.length === 0 || only.includes(n))
  .sort();

const profile = mkdtempSync(join(tmpdir(), "grove-examples-"));
const proc = spawn(chrome, [
  "--headless=new",
  "--disable-gpu",
  "--no-first-run",
  "--no-default-browser-check",
  "--window-size=1200,900",
  "--remote-debugging-port=0",
  `--user-data-dir=${profile}`,
  "about:blank",
], { stdio: ["ignore", "ignore", "pipe"] });

const browserWs = await new Promise((resolve, reject) => {
  let buf = "";
  proc.stderr.on("data", (d) => {
    buf += d;
    const m = buf.match(/DevTools listening on (ws:\/\/\S+)/);
    if (m) resolve(m[1]);
  });
  proc.on("exit", (code) => reject(new Error(`chromium exited ${code}\n${buf}`)));
});
const port = new URL(browserWs).port;

// one CDP connection per page target
async function connect(wsUrl) {
  const ws = new WebSocket(wsUrl);
  await new Promise((r) => ws.addEventListener("open", r, { once: true }));
  let nextId = 1;
  const pending = new Map();
  const listeners = new Map();
  ws.addEventListener("message", ({ data }) => {
    const msg = JSON.parse(data);
    if (msg.id && pending.has(msg.id)) {
      const { resolve, reject } = pending.get(msg.id);
      pending.delete(msg.id);
      msg.error ? reject(new Error(JSON.stringify(msg.error))) : resolve(msg.result);
    } else if (msg.method) {
      for (const fn of listeners.get(msg.method) || []) fn(msg.params);
    }
  });
  const send = (method, params = {}) => new Promise((resolve, reject) => {
    const id = nextId++;
    pending.set(id, { resolve, reject });
    ws.send(JSON.stringify({ id, method, params }));
  });
  const on = (method, fn) => {
    if (!listeners.has(method)) listeners.set(method, []);
    listeners.get(method).push(fn);
  };
  return { ws, send, on };
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const norm = (s) => s.replace(/\s+/g, " ").trim();
const bodyText = `document.body.innerText.replace(/\\s+/g, " ")`;

async function newPage() {
  const target = await (await fetch(`http://127.0.0.1:${port}/json/new?about:blank`, { method: "PUT" })).json();
  const cdp = await connect(target.webSocketDebuggerUrl);
  await cdp.send("Runtime.enable");
  await cdp.send("Page.enable");
  // focus/blur behave as if the tab had focus, even with several tabs open
  await cdp.send("Emulation.setFocusEmulationEnabled", { enabled: true });
  return { targetId: target.id, ...cdp };
}

function describeArgs(args) {
  return args.map((a) => a.value ?? a.description ?? a.type).join(" ");
}

// the API scenarios use
function makeT(page, name) {
  const errors = [];
  const expected = [];
  const logs = [];

  page.on("Runtime.exceptionThrown", ({ exceptionDetails }) => {
    const text = exceptionDetails.exception?.description || exceptionDetails.text;
    errors.push(text);
  });
  page.on("Runtime.consoleAPICalled", ({ type, args }) => {
    const text = describeArgs(args);
    logs.push(`${type}: ${text}`);
    if (type === "error") errors.push(text);
  });

  const evaluate = async (expression) => {
    const res = await page.send("Runtime.evaluate", { expression, awaitPromise: true, returnByValue: true });
    if (res.exceptionDetails) {
      throw new Error(`evaluate failed: ${expression}\n${res.exceptionDetails.exception?.description || res.exceptionDetails.text}`);
    }
    return res.result.value;
  };

  // finds an element by css selector, or by visible text when given {text}
  const findExpr = (target) => {
    if (typeof target === "string") {
      return `document.querySelector(${JSON.stringify(target)})`;
    }
    const { text, selector = "button, a, label, [role], li, div, span, td" } = target;
    // exact text first, else the most specific (shortest) element containing it
    return `(() => {
      const els = [...document.querySelectorAll(${JSON.stringify(selector)})]
        .filter((el) => el.offsetParent !== null || el.getClientRects().length);
      return els.find((el) => el.textContent.trim() === ${JSON.stringify(text)})
        || els.filter((el) => el.textContent.includes(${JSON.stringify(text)}))
              .sort((a, b) => a.textContent.length - b.textContent.length)[0];
    })()`;
  };

  const t = {
    name,
    base: BASE,
    logs,
    evaluate,
    sleep,

    // raw CDP command for this page
    cdp(method, params = {}) {
      return page.send(method, params);
    },

    async goto(path) {
      const loaded = new Promise((r) => page.on("Page.loadEventFired", r));
      await page.send("Page.navigate", { url: BASE + path });
      await loaded;
      // the prose and code of the page (examples.common/doc-html) would match text checks
      await evaluate(`document.head.insertAdjacentHTML("beforeend", "<style>[data-doc]{display:none!important}</style>")`);
    },

    async waitFor(predicateExpr, { timeout = 5000, message } = {}) {
      const start = Date.now();
      while (Date.now() - start < timeout) {
        if (await evaluate(`!!(${predicateExpr})`)) return;
        await sleep(25);
      }
      throw new Error(message || `timeout waiting for ${predicateExpr}`);
    },

    // visible text with whitespace collapsed, so layout (flex items on their own line) doesn't matter
    async waitText(text, opts = {}) {
      await t.waitFor(`${bodyText}.includes(${JSON.stringify(norm(text))})`,
        { message: `timeout waiting for text "${text}"`, ...opts });
    },

    async waitNoText(text, opts = {}) {
      await t.waitFor(`!${bodyText}.includes(${JSON.stringify(norm(text))})`,
        { message: `timeout waiting for text "${text}" to disappear`, ...opts });
    },

    async text(target = "body") {
      return evaluate(`(${findExpr(target)})?.innerText.replace(/\\s+/g, " ").trim()`);
    },

    async exists(target) {
      return evaluate(`!!(${findExpr(target)})`);
    },

    async click(target) {
      await t.waitFor(findExpr(target), { message: `can't find ${JSON.stringify(target)} to click` });
      await evaluate(`(${findExpr(target)}).click()`);
      await t.settle();
    },

    async focus(target) {
      await t.waitFor(findExpr(target), { message: `can't find ${JSON.stringify(target)} to focus` });
      await evaluate(`(${findExpr(target)}).focus()`);
    },

    // types like a user into the focused element (input events fire per insert)
    async type(target, text) {
      await t.focus(target);
      for (const ch of text) {
        await page.send("Input.insertText", { text: ch });
      }
      await t.settle();
    },

    // replaces the value of an input like a user selecting all and typing
    async fill(target, text) {
      await t.focus(target);
      await evaluate(`(${findExpr(target)}).select?.()`);
      await page.send("Input.insertText", { text });
      await t.settle();
    },

    // key("Enter"), key("ArrowDown"), key("Enter", ["ctrl"])
    async key(key, modifiers = []) {
      const mods = { alt: 1, ctrl: 2, meta: 4, shift: 8 };
      const modifierBits = modifiers.reduce((m, k) => m | mods[k], 0);
      const codes = { Enter: 13, Escape: 27, ArrowUp: 38, ArrowDown: 40, ArrowLeft: 37, ArrowRight: 39,
        PageUp: 33, PageDown: 34, Home: 36, End: 35, Tab: 9 };
      const code = codes[key] ?? key.toUpperCase().charCodeAt(0);
      const base = { key, code: key.length === 1 ? `Key${key.toUpperCase()}` : key,
        windowsVirtualKeyCode: code, nativeVirtualKeyCode: code, modifiers: modifierBits };
      await page.send("Input.dispatchKeyEvent", { type: "rawKeyDown", ...base });
      if (key === "Enter") await page.send("Input.dispatchKeyEvent", { type: "char", text: "\r", ...base });
      await page.send("Input.dispatchKeyEvent", { type: "keyUp", ...base });
      await t.settle();
    },

    // lets microtasks, the render and next-tick fx run
    // rAF doesn't fire in background tabs, so it races a timeout
    async settle(ms = 30) {
      await sleep(ms);
      await evaluate(`new Promise((r) => { requestAnimationFrame(() => setTimeout(r, 0)); setTimeout(r, 100); })`);
    },

    // the examples' log panel (examples.common/log!)
    async log() {
      return evaluate(`document.querySelector(".examples-log")?.innerText ?? ""`);
    },

    async clearLog() {
      await evaluate(`document.querySelector(".examples-log").textContent = ""`);
    },

    // log lines since the last clearLog, after running fn
    async logOf(fn) {
      await t.clearLog();
      await fn();
      await t.settle();
      return t.log();
    },

    // the × render badge inside the element found by target
    async renders(target) {
      return evaluate(`Number((${findExpr(target)})?.querySelector("[title='how often this ran']")?.textContent.replace("× ", ""))`);
    },

    expectConsoleError(pattern) {
      expected.push(pattern);
    },

    assert(cond, message) {
      if (!cond) throw new Error(`assertion failed: ${message}`);
    },

    // a library issue the example's text describes. listed at the end while it is still
    // observed, fails once it no longer is, so the text gets updated with the fix
    knownIssue(observed, message) {
      if (!observed) throw new Error(`known issue no longer observed, update the example text: ${message}`);
      knownIssues.push(`${name}: ${message}`);
    },

    async assertText(text) {
      const body = await evaluate(bodyText);
      if (!body.includes(norm(text))) throw new Error(`expected page to contain "${text}"`);
    },

    async assertNoText(text) {
      const body = await evaluate(bodyText);
      if (body.includes(norm(text))) throw new Error(`expected page not to contain "${text}"`);
    },

    // second tab, same browser profile. returns a t for it
    async openTab(path) {
      const other = await newPage();
      const t2 = makeT(other, `${name} (tab 2)`);
      t2.close = () => fetch(`http://127.0.0.1:${port}/json/close/${other.targetId}`);
      if (path) await t2.goto(path);
      return t2;
    },

    // errors seen so far that no expectConsoleError matched
    unexpectedErrors() {
      return errors.filter((e) => !expected.some((p) => p.test(e)));
    },

    // checks and clears errors, for scenarios that provoke them on purpose
    takeErrors() {
      const all = errors.splice(0, errors.length);
      return all;
    },
  };
  return t;
}

let failed = 0;
const startAll = Date.now();
const knownIssues = [];

for (const name of names) {
  const page = await newPage();
  const t = makeT(page, name);
  const start = Date.now();
  try {
    const { default: scenario } = await import(pathToFileURL(join(scenarioDir, `${name}.mjs`)).href);
    let timer;
    await Promise.race([
      scenario(t),
      new Promise((_, reject) => { timer = setTimeout(() => reject(new Error("scenario took longer than 60s")), 60000); }),
    ]).finally(() => clearTimeout(timer));
    const errs = t.unexpectedErrors();
    if (errs.length) throw new Error(`console errors:\n  ${errs.join("\n  ")}`);
    console.log(`ok    ${name} (${Date.now() - start}ms)`);
  } catch (e) {
    failed++;
    console.log(`FAIL  ${name} (${Date.now() - start}ms)\n  ${String(e.message).split("\n").join("\n  ")}`);
    const recent = t.logs.slice(-15);
    if (recent.length) console.log(`  last console output:\n    ${recent.join("\n    ")}`);
  } finally {
    page.ws.close();
    await fetch(`http://127.0.0.1:${port}/json/close/${page.targetId}`).catch(() => {});
  }
}

if (knownIssues.length) {
  console.log(`\nknown issues (${knownIssues.length}):\n  ${knownIssues.join("\n  ")}`);
}
console.log(`\n${names.length - failed}/${names.length} scenarios passed (${Date.now() - startAll}ms)`);
proc.kill();
rmSync(profile, { recursive: true, force: true });
process.exit(failed ? 1 : 0);

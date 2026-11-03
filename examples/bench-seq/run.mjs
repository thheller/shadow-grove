// runs the :bench-seq release build in headless chromium via CDP, prints markdown tables
// usage: node examples/bench-seq/run.mjs [--sizes=10,100] [--scenarios=swap,shuffle] [--json=out.json]
import { spawn } from "node:child_process";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, dirname } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const args = Object.fromEntries(
  process.argv.slice(2).map((a) => a.replace(/^--/, "").split("=")));

const opts = {};
if (args.sizes) opts.sizes = args.sizes.split(",").map(Number);
if (args.scenarios) opts.scenarios = args.scenarios.split(",");

const chrome = process.env.CHROME || "/usr/bin/chromium";
const page = pathToFileURL(join(dirname(fileURLToPath(import.meta.url)), "index.html")).href;
const profile = mkdtempSync(join(tmpdir(), "bench-seq-"));

const proc = spawn(chrome, [
  "--headless=new",
  "--disable-gpu",
  "--no-first-run",
  "--no-default-browser-check",
  "--js-flags=--expose-gc",
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
const targets = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
const ws = new WebSocket(targets.find((t) => t.type === "page").webSocketDebuggerUrl);
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
  } else if (msg.method && listeners.has(msg.method)) {
    listeners.get(msg.method)(msg.params);
  }
});
const send = (method, params = {}) => new Promise((resolve, reject) => {
  const id = nextId++;
  pending.set(id, { resolve, reject });
  ws.send(JSON.stringify({ id, method, params }));
});

listeners.set("Runtime.consoleAPICalled", ({ args }) =>
  process.stderr.write(args.map((a) => a.value ?? a.description).join(" ") + "\n"));

try {
  await send("Runtime.enable");
  await send("Page.enable");
  const loaded = new Promise((r) => listeners.set("Page.loadEventFired", r));
  await send("Page.navigate", { url: page });
  await loaded;

  const res = await send("Runtime.evaluate", {
    expression: `window.runBench(${JSON.stringify(opts)})`,
    awaitPromise: true,
    returnByValue: true,
  });
  if (res.exceptionDetails) throw new Error(JSON.stringify(res.exceptionDetails, null, 2));
  const result = res.result.value;

  if (args.json) writeFileSync(args.json, JSON.stringify(result, null, 2));

  const ver = await send("Browser.getVersion");
  console.log(`# seq bench (${ver.product})\n`);

  const fmt = (x) => x >= 100 ? x.toFixed(0) : x >= 10 ? x.toFixed(1) : x.toFixed(2);

  console.log("## matching only, µs per op (lookup build + match all)\n");
  for (const n of [...new Set(result.matching.map((r) => r.n))]) {
    const rows = result.matching.filter((r) => r.n === n);
    const strats = [...new Set(rows.map((r) => r.strategy))];
    const datas = [...new Set(rows.map((r) => r.data))];
    console.log(`### n=${n}\n`);
    console.log(`| data | ${strats.join(" | ")} |`);
    console.log(`|---|${strats.map(() => "---:").join("|")}|`);
    for (const d of datas) {
      const cells = strats.map((s) => {
        const r = rows.find((r) => r.data === d && r.strategy === s);
        return `${fmt(r.us)} (${r.matched}/${n})`;
      });
      console.log(`| ${d} | ${cells.join(" | ")} |`);
    }
    console.log("");
  }

  console.log("## DOM, per op: µs script / µs incl. forced layout / renders / DOM mutations\n");
  for (const n of [...new Set(result.dom.map((r) => r.n))]) {
    const rows = result.dom.filter((r) => r.n === n);
    const impls = [...new Set(rows.map((r) => r.impl))];
    const scenarios = [...new Set(rows.map((r) => r.scenario))];
    console.log(`### n=${n}\n`);
    console.log(`| scenario | ${impls.join(" | ")} |`);
    console.log(`|---|${impls.map(() => "---:").join("|")}|`);
    for (const s of scenarios) {
      const cells = impls.map((i) => {
        const r = rows.find((r) => r.scenario === s && r.impl === i);
        return r ? `${fmt(r.us)} / ${fmt(r["us-layout"])} / ${r.renders} / ${r.mutations}` : "n/a";
      });
      console.log(`| ${s} | ${cells.join(" | ")} |`);
    }
    console.log("");
  }
} finally {
  ws.close();
  if (proc.exitCode === null) {
    const exited = new Promise((r) => proc.once("exit", r));
    proc.kill();
    await exited;
  }
  rmSync(profile, { recursive: true, force: true, maxRetries: 5 });
}

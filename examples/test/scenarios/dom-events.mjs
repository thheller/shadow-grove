// events in markup; :e/* options
export default async (t) => {
  await t.goto("/dom-events/");
  await t.waitText("what :on-* accepts");
  const count = (line) => t.log().then((l) => l.split("\n").filter((x) => x.includes(line)).length);
  const clicks = () => t.text({ text: "clicks per label", selector: "p" });

  await t.click({ text: "deftx map", selector: "button" });
  t.assert((await clicks()).includes("{:deftx 1}"), "deftx map runs the tx");
  let log = await t.logOf(() => t.click({ text: "{:e ::local!}", selector: "button" }));
  t.assert(log.includes("slot ::local! got {:extra 1}"), "map to a slot, extra data rides along");
  log = await t.logOf(() => t.click({ text: "::local! keyword", selector: "button" }));
  t.assert(log.includes("slot ::local! got {}"), "keyword is short for {:e kw}");
  log = await t.logOf(() => t.click({ text: "top-level fn", selector: "button" }));
  t.assert(log.includes("plain fn called with a click"), "fn called with [dom-event env]");

  await t.clearLog();
  for (let i = 0; i < 3; i++) await t.click({ text: "re-render", selector: "button" });
  await t.click({ text: "inline fn", selector: "button" });
  const inlineCalls = await count("inline fn called");
  t.assert(inlineCalls >= 1, "inline fn called");
  // fragments install one listener that reads the latest value, so a new fn per render doesn't stack
  t.assert(inlineCalls === 1, `an inline fn handler runs once per click after re-renders (ran ${inlineCalls}x)`);
  t.assert((await t.log()).includes("inline fn called, n = 3"), "and sees the latest closure");

  await t.click({ text: "(when x ev): armed", selector: "button" });
  await t.click({ text: "arm/disarm", selector: "button" });
  await t.click({ text: "(when x ev): nil", selector: "button" });
  t.assert((await clicks()).includes(":maybe 1"), "nil handler ignores the click");

  log = await t.logOf(() => t.click({ text: ":e/prevent-default", selector: "a" }));
  t.assert(log.includes("defaultPrevented = true") && await t.evaluate("location.hash") === "", ":e/prevent-default");
  log = await t.logOf(() => t.click({ text: "bubbles", selector: "button" }));
  t.assert(log.includes("inner click") && log.includes("outer div click"), "events bubble in the DOM");
  log = await t.logOf(() => t.click({ text: ":e/stop", selector: "button" }));
  t.assert(log.includes("inner click") && !log.includes("outer div click"), ":e/stop");

  await t.clearLog();
  await t.type("input[placeholder^=':e/debounce']", "abc");
  await t.sleep(400);
  t.assert(await count("tx search! ran") === 1 && (await t.log()).includes("ran with abc"), ":e/debounce from the deftx base map");

  for (const label of [":e/throttle 1000", ":e/rate-limit 1000", ":e/once"]) {
    for (let i = 0; i < 3; i++) await t.click({ text: label, selector: "button" });
  }
  await t.sleep(1100);
  const c = await clicks();
  const n = (k) => Number((c.match(new RegExp(`:${k} (\\d+)`)) || [])[1] || 0);
  t.assert(n("throttle") >= 1 && n("throttle") <= 2, `throttle: ${c}`);
  t.assert(n("rate-limit") === 1, `rate-limit: ${c}`);
  t.assert(n("once") === 1, `once: ${c}`);
};

// pipeline, equality short-circuit, destructured args, seeds
export default async (t) => {
  await t.goto("/defc-basics/");
  await t.waitText("Hello, Ada Lovelace");

  const section = (n) => ({ text: n, selector: "section" });
  const row = (text) => ({ text, selector: "[data-demo] > div" });

  // 1. a bind only re-runs when what it uses changed
  let log = await t.logOf(() => t.click({ text: "toggle first name", selector: "button" }));
  t.assert(log.includes("bind full-name ran"), "full-name re-ran");
  t.assert(!log.includes("bind shout ran"), "shout doesn't use first-name");
  t.assert(log.includes("greeting: render"), "render ran");

  log = await t.logOf(() => t.click({ text: "set first name to the same value", selector: "button" }));
  t.assert(log.trim() === "", `same value runs nothing, got:\n${log}`);

  // 2. n + 2 runs even? but stops there
  const parity = () => t.renders(row("n is"));
  const before = await parity();
  log = await t.logOf(() => t.click({ text: "n + 2", selector: "button" }));
  t.assert(log.includes("bind even? ran"), "even? ran");
  t.assert(!log.includes("bind label ran"), "label didn't run");
  t.assert(await parity() === before, "parity didn't render");
  await t.click({ text: "n + 1", selector: "button" });
  t.assert(await parity() === before + 1, "n + 1 renders");

  // 3. keys the arg vector doesn't bind never reach the component
  const plain = () => t.renders(row("only binds :a"));
  const withAs = () => t.renders(row("binds :a and :as m"));
  const [p0, a0] = [await plain(), await withAs()];
  await t.click({ text: "b + 1", selector: "button" });
  t.assert(await plain() === p0, "b + 1 didn't reach the destructured component");
  t.assert(await withAs() === a0 + 1, ":as keeps the map, so b + 1 updates it");

  // 4. seeds
  const kind = () => t.renders(row("component A"));
  const k0 = await kind();
  await t.click({ text: "same args", selector: "button" });
  t.assert(await kind() === k0, "equal seed does nothing");
  log = await t.logOf(() => t.click({ text: "new args", selector: "button" }));
  t.assert(await kind() === k0 + 1, "new args update in place");
  t.assert(!log.includes("mounted"), "no remount for new args");
  log = await t.logOf(() => t.click({ text: "other component", selector: "button" }));
  t.assert(log.includes("kind-a: unmounted") && log.includes("kind-b: mounted"), `other definition remounts, got:\n${log}`);
  await t.assertText("component B");
};

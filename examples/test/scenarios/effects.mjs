// effect deps, cleanup, dom scheduler, track-change
export default async (t) => {
  await t.goto("/effects/");
  await t.waitText("a = 0, b = 0");
  let log = await t.log();
  t.assert(["effect :mount ran", "effect :render ran", "effect :auto ran", "effect [a] ran"].every((s) => log.includes(s)), `all run on mount:\n${log}`);

  log = await t.logOf(() => t.click({ text: "a + 1", selector: "button" }));
  t.assert(log.includes("effect :render ran") && log.includes("effect :auto ran") && log.includes("effect [a] ran"), `a + 1:\n${log}`);
  t.assert(log.includes("cleanup :render") && log.includes("cleanup :auto") && log.includes("cleanup [a]"), "cleanup before the next run");
  t.assert(!log.includes("effect :mount ran"), ":mount only once");

  log = await t.logOf(() => t.click({ text: "b + 1", selector: "button" }));
  t.assert(log.includes("effect :render ran"), ":render runs on every render");
  t.assert(!log.includes("effect :auto ran"), ":auto doesn't run when only b (not used in its body) changed");
  t.knownIssue(log.includes("effect [a] ran"), "an effect with deps a also runs when only b changed (its body uses b), the previous deps aren't stored");

  log = await t.logOf(() => t.click({ text: "unmount", selector: "button" }));
  t.assert(log.includes("cleanup :mount (unmount)"), "cleanup on unmount");

  await t.waitText("box is");
  await t.click({ text: "down 10", selector: "button" });
  await t.click({ text: "down 10", selector: "button" });
  await t.waitText("active 20");
  log = await t.log();
  t.assert(log.includes("track-change: active 10 -> 20"), "track-change got old and new");
  await t.sleep(100);
  const visible = await t.evaluate(`(() => {
    const el = document.querySelector("[data-active]");
    const box = el.parentElement.getBoundingClientRect(), r = el.getBoundingClientRect();
    return r.top >= box.top && r.bottom <= box.bottom; })()`);
  t.assert(visible, "active item scrolled into view");
};

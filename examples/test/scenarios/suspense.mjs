// suspense: offscreen vs in place, :timeout, chrome beside, suspend! with a default, no boundary
export default async (t) => {
  // known issue: the fallback swap after :timeout runs from a setTimeout outside any devtools trace,
  // shadow.grove.preload logs "not tracing?" for the component destroy it does
  t.expectConsoleError(/not tracing\?/);
  await t.goto("/suspense/");
  await t.waitFor(`document.querySelectorAll("[data-page]").length === 2`, { message: "both pages rendered" });
  const page = (where) => t.evaluate(`document.querySelector("[data-page=${where}]")?.innerText ?? null`);
  const fallback = (where) => t.exists(`[data-fallback=${where}]`);

  // direct child: rendered offscreen, the old page stays
  await t.click({ text: "fast", selector: "button" });
  t.assert((await page("direct"))?.includes("page home"), "direct: old page stays while the new one suspends");
  t.assert(!(await page("wrapped")), "wrapped: the fragment updates in place, the old page is gone right away");
  await t.waitFor(`document.querySelector("[data-page=direct]")?.innerText.includes("page fast")`);
  t.assert(!(await fallback("direct")), "direct: fast page never showed the fallback");
  t.assert((await page("wrapped"))?.includes("page fast"), "wrapped: shows the new page once ready");

  // slow: the fallback after :timeout
  await t.click({ text: "slow", selector: "button" });
  await t.sleep(300);
  t.assert((await page("direct"))?.includes("page fast"), "direct: still the old page at 300ms");
  await t.waitFor(`!!document.querySelector("[data-fallback=direct]")`, { timeout: 1000, message: "direct: fallback after :timeout" });
  t.assert(await t.exists("[data-clock=beside]"), "chrome beside the boundaries stays");
  t.assert(!(await fallback("wrapped")), "wrapped: no fallback, the timeout only applies to offscreen work");
  await t.waitFor(`document.querySelector("[data-page=direct]")?.innerText.includes("page slow")`, { timeout: 3000 });

  // suspend! default: the bind sees the default on the first run
  t.assert((await t.log()).includes('suspend-default: bind got {:status "default from suspend!"}'), "first run got the default");
  await t.click({ text: "toggle ready?", selector: "button" });
  await t.waitText('value: {:status "ready"}');
  const log = await t.logOf(() => t.click({ text: "toggle ready?", selector: "button" }));
  t.assert(log.includes('suspend-default: bind got {:status "ready"}'), `later runs get the previous value:\n${log}`);

  // no boundary
  await t.click({ text: "mount a suspending component", selector: "button" });
  await t.sleep(1500);
  const late = await t.exists("#late");
  t.assert(late, "without a suspense ancestor the component mounts once its data arrived");
  t.knownIssue(t.logs.some((l) => /not tracing\?/.test(l)), 'the fallback swap after :timeout logs "not tracing?" with the devtools preload');
};

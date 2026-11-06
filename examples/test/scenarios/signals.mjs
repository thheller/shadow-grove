// signals (flex) integrated as a managed node and a bind helper
export default async (t) => {
  await t.goto("/signals/");
  await t.waitText("count: 0, doubled: 0");
  await t.assertText("counter / 5 = 0 (even)");
  await t.assertText("twice: 0, thrice: 0");

  const inc = { text: "counter + 1", selector: "button" };
  const incOther = { text: "other + 1", selector: "button" };
  const badge = () => t.evaluate(`document.querySelector("[data-markup]").parentElement.textContent`);

  await t.evaluate(`document.querySelector("[data-markup]").__mark = true`);
  let log = await t.logOf(() => t.click(inc));
  await t.waitText("count: 1, doubled: 2");
  t.assert(log.includes("node wrote"), `the node wrote:\n${log}`);
  t.assert(await t.evaluate(`document.querySelector("[data-markup]").__mark === true`), "the element around the node stayed");
  t.assert((await badge()).includes("× 1"), `the component around the nodes didn't render: ${await badge()}`);

  // two writes in one event, one scheduled write per node
  log = await t.logOf(() => t.click({ text: "counter + 2", selector: "button" }));
  await t.waitText("count: 3, doubled: 6");
  t.assert(!/node wrote [24]\b/.test(log), `no write for the values in between:\n${log}`);
  t.assert(log.match(/node wrote/g).length === 3, `counter, doubled and twice wrote once each:\n${log}`);
  t.assert((log.match(/twice ran/g) || []).length === 2, `the signal itself still ran per change:\n${log}`);

  // the signal is the cutoff for the bind
  const fives = () => t.evaluate(`document.querySelector("[data-fives]").parentElement.textContent`);
  const before = await fives();
  t.assert(before.includes("counter / 5 = 0 (even)") && before.includes("× 1"), `4 increments, no render: ${before}`);
  await t.click(inc);
  await t.waitText("count: 4, doubled: 8");
  t.assert((await fives()).includes("× 1"), "still no render at 4");
  await t.click(inc);
  await t.waitText("counter / 5 = 1 (odd)");
  t.assert((await fives()).includes("× 2"), `renders at 5: ${await fives()}`);

  // owned signals follow src
  log = await t.logOf(() => t.click(inc));
  t.assert(log.includes("twice ran") && log.includes("thrice ran"), `both run:\n${log}`);
  await t.click({ text: "use other", selector: "button" });
  await t.waitText("twice: 200, thrice: 300");
  log = await t.logOf(() => t.click(inc));
  t.assert(!log.includes("twice ran") && !log.includes("thrice ran"), `moved off counter:\n${log}`);
  log = await t.logOf(() => t.click(incOther));
  t.assert(log.includes("twice ran") && log.includes("thrice ran"), `moved to other:\n${log}`);
  await t.waitText("twice: 202, thrice: 303");

  // unmount releases everything
  await t.click({ text: "hide", selector: "button" });
  await t.assertNoText("thrice:");
  log = await t.logOf(() => t.click(incOther));
  t.assert(!log.includes("ran"), `nothing runs after hide:\n${log}`);
  log = await t.logOf(() => t.click(inc));
  t.assert(!log.includes("ran"), `nothing runs after hide:\n${log}`);

  await t.click({ text: "show", selector: "button" });
  await t.waitText("twice: 204, thrice: 306");
};

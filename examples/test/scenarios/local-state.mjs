// atom in a ref read with sg/watch, use-state
export default async (t) => {
  await t.goto("/local-state/");
  await t.waitText("outside atom");

  await t.click({ text: "open", selector: "button" });
  await t.waitText("a = 0");
  const row = (k) => t.renders({ text: `${k} = `, selector: "[data-demo] div" });
  const parent = () => t.renders({ text: "close", selector: "[data-demo] > div" });
  const [a0, b0, p0] = [await row("a"), await row("b"), await parent()];
  await t.click({ text: "a + 1", selector: "button" });
  await t.waitText("a = 1");
  t.assert(await row("a") === a0 + 1, "a row re-rendered");
  t.assert(await row("b") === b0, "b row didn't re-render");
  t.assert(await parent() === p0, "parent didn't re-render, its watched path is :open?");

  await t.click({ text: "close", selector: "button" });
  await t.click({ text: "open", selector: "button" });
  await t.waitText("a = 1");

  const tens = () => t.renders({ text: "tens:", selector: "[data-demo] > div" });
  const t0 = await tens();
  for (let i = 0; i < 9; i++) await t.click({ text: "n + 1", selector: "button" });
  t.assert(await tens() === t0, "n 0..9 doesn't change (quot n 10)");
  await t.click({ text: "n + 1", selector: "button" });
  t.assert(await tens() === t0 + 1, "n = 10 renders");

  const outside = await t.text({ text: "outside atom =", selector: "[data-demo] > div" });
  await t.sleep(1100);
  t.assert(await t.text({ text: "outside atom =", selector: "[data-demo] > div" }) !== outside, "outside atom updates");

  await t.type("input[placeholder='type here']", "abc");
  await t.waitText("length 3");
};

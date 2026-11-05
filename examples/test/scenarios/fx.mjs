// queue-fx / reg-fx, fx-timeout
export default async (t) => {
  await t.goto("/fx/");
  await t.waitText("order: tx, render, fx, follow-up tx");
  const log = await t.logOf(async () => {
    await t.click({ text: "run tx with fx", selector: "button" });
    await t.waitText(":done");
  });
  const at = (s) => log.indexOf(s);
  t.assert(at("tx with-fx! ran") >= 0, "tx ran");
  t.assert(at("tx with-fx! ran") < at("render, n = 1"), "render after the tx");
  t.assert(at("render, n = 1") < at("fx ::work ran"), `fx after the render:\n${log}`);
  t.assert(at("fx ::work ran") < at("tx fx-done! got"), "result re-enters as a tx");

  await t.click({ text: "show notice", selector: "button" });
  t.assert(await t.exists("#notice"), "notice shown");
  await t.sleep(2200);
  t.assert(!(await t.exists("#notice")), "fx-timeout hid it");
  await t.click({ text: "show notice", selector: "button" });
  await t.click({ text: "keep it", selector: "button" });
  await t.sleep(2200);
  t.assert(await t.exists("#notice"), "fx-timeout-clear cancelled it");

  await t.click({ text: "show and focus an input", selector: "button" });
  await t.waitFor(`document.activeElement?.id === "late-input"`, { message: "fx ran after the input rendered" });

  t.expectConsoleError(/unknown fx/);
  await t.click({ text: "queue an unregistered fx", selector: "button" });
  await t.settle(50);
  t.assert(t.takeErrors().some((e) => /unknown fx/.test(e)), "unknown fx throws when it runs");
};

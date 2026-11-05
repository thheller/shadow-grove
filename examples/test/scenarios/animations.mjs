// animations; dispatch-up! after a leave animation
export default async (t) => {
  await t.goto("/animations/");
  await t.waitText("enter and leave");
  const toasts = () => t.evaluate(`[...document.querySelectorAll("button")].filter((b) => b.textContent === "dismiss").length`);

  await t.click({ text: "add toast", selector: "button" });
  await t.waitText("toast 0");
  let log = await t.logOf(() => t.click({ text: "dismiss", selector: "button" }));
  t.assert(log.includes("slot ::toast-kill! intercepted") && !log.includes("tx toast-kill! ran"), "slot holds the tx back");
  t.assert(await toasts() === 1, "toast still there while animating");
  await t.sleep(450);
  t.assert((await t.log()).includes("tx toast-kill! ran"), "tx ran after on-finish");
  t.assert(await toasts() === 0, "toast removed");

  await t.click({ text: "add toast", selector: "button" });
  await t.waitText("toast 0");
  await t.click({ text: "remove now", selector: "button" });
  t.assert(await toasts() === 0, "remove now skips the animation");

  await t.click({ text: "shake", selector: "button" });
  await t.sleep(500);
  t.assert((await t.log()).includes("shake finished"), "sg/animate :on-finish");
};

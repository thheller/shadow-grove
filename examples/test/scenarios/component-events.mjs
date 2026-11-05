// event slots, bubbling, run-tx vs dispatch-up!, unhandled events
export default async (t) => {
  await t.goto("/component-events/");
  await t.waitText("count (written by the count! tx): 0");

  let log = await t.logOf(() => t.click({ text: "no slot", selector: "button" }));
  t.assert(log.includes("tx count! ran from plain , DOM event: click"), `root runs the deftx:\n${log}`);

  log = await t.logOf(() => t.click({ text: "child button, parent slot", selector: "button" }));
  t.assert(log.includes("parent slot ::local! handled event from child"), "bubbles to the parent slot");

  log = await t.logOf(() => t.click({ text: "slot swallows", selector: "button" }));
  t.assert(log.includes("slot swallowed") && !log.includes("tx count! ran"), "slot stops the tx");

  log = await t.logOf(() => t.click({ text: "slot + run-tx", selector: "button" }));
  t.assert(log.includes("tx count! ran from run-tx , DOM event: click"), `run-tx keeps e:\n${log}`);

  log = await t.logOf(() => t.click({ text: "slot + dispatch-up!", selector: "button" }));
  t.assert(log.includes("tx count! ran from dispatch-up , DOM event: nil"), `dispatch-up! drops e:\n${log}`);
  await t.waitText("count (written by the count! tx): 3");

  await t.click({ text: "red", selector: "button" });
  await t.waitText("picked: :red");

  t.expectConsoleError(/Unhandled Event|nobody-handles-this/);
  await t.click({ text: "unhandled event", selector: "button" });
  await t.settle(50);
  const logs = t.logs.join("\n");
  t.assert(/Unhandled Event/.test(logs), "unhandled events are reported");
};

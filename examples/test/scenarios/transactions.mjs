// deftx, reg-event, sg/run, tx-env, results
export default async (t) => {
  await t.goto("/transactions/");
  await t.waitText("event maps are data");
  const value = () => t.text("[data-value]");
  const click = (label) => t.click({ text: label, selector: "button" });
  const ev = await t.text("#event-map");
  t.assert(ev.includes("{:e :examples.transactions/add!, :args [5]}"), `event map: ${ev}`);
  t.assert(ev.includes(":source :tagged-button"), "base map keys");
  t.assert(ev.includes(":shadow.grove/tx"), "handler in metadata");

  await click("add 5");
  t.assert(await value() === "5", "deftx");
  let log = await t.logOf(() => click("add 1 (tagged)"));
  t.assert(log.includes("sees :source :tagged-button") && await value() === "6", "handler sees base map keys");
  await click("add {:x 1 :y 2}");
  t.assert(await value() === "9", "destructured constructor args");
  log = await t.logOf(() => click("add 10 twice (sg/run)"));
  t.assert(await value() === "29", "sg/run chains");
  t.assert(log.includes("chain: [:examples.transactions/add! :examples.transactions/add!]"), `::sg/chain:\n${log}`);
  await click("reg-event ::reset! to 100");
  t.assert(await value() === "100", "reg-event");

  await click("return nil");
  await click("return tx-env");
  t.assert(await value() === "100", "no-ops");
  t.expectConsoleError(/FAILED TO PROCESS EVENT|invalid result|Error/);
  await click("return another map");
  t.assert(await value() === "100", "invalid result commits nothing");
  t.assert(t.takeErrors().some((e) => /invalid result|FAILED/.test(e)), "invalid result is reported");

  log = await t.logOf(() => click("log tx-env keys (from a click)"));
  t.assert(log.includes("tables: (:counter :other)"), `tables in tx-env:\n${log}`);
  t.assert(log.includes(":shadow.grove/runtime-ref") && log.includes(":shadow.grove/fx"), "framework keys");
  t.assert(log.includes("dom event: click"), "e from the DOM");
  log = await t.logOf(async () => { await click("same via run-tx! from a timeout"); await t.sleep(200); });
  t.assert(log.includes("dom event: nil"), "run-tx! has no DOM event");
};

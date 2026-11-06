// a real socket to examples.server. other open tabs are other clients, so checks go by client id
export default async (t) => {
  await t.goto("/websocket/");
  await t.waitText("connected as client");
  const idOf = async (tab) => (await tab.text("#status")).match(/client (\d+)/)[1];
  const id = await idOf(t);
  t.assert(!(await t.exists("#banner")), "no banner while connected");

  // welcome -> hello + request-clients -> the client table
  await t.waitFor(`document.querySelector("[data-client='${id}']")?.innerText.includes("this tab")`, { message: "own client listed" });

  // a cast writes nothing, the push from the server does
  const log = await t.logOf(async () => {
    await t.click({ text: "say hello", selector: "button" });
    await t.waitFor(`document.querySelector("#messages").innerText.includes("hello from")`);
  });
  t.assert(log.includes("send") && log.includes(":ws/say"), `cast logged:\n${log}`);
  t.assert(log.includes("received") && log.includes(":ws/said"), `push logged:\n${log}`);

  // a second tab shows up, messages reach it, closing it is noticed
  const t2 = await t.openTab("/websocket/");
  await t2.waitText("connected as client");
  const id2 = await idOf(t2);
  await t.waitFor(`document.querySelector("[data-client='${id2}']")`, { message: ":client-connect notify" });
  await t2.waitFor(`document.querySelector("[data-client='${id}']")`, { message: ":ws/clients has the first tab" });

  await t2.click({ text: "say hello", selector: "button" });
  await t.waitFor(`document.querySelector("#messages").innerText.includes("[${id2} ")`, { message: "push from the other tab" });

  // ping is answered without a tx or log entry, the server counts the pongs
  const pingLog = await t.logOf(() => t.sleep(2300));
  t.assert(!pingLog.includes("ping") && !pingLog.includes("pong"), `pings not logged:\n${pingLog}`);

  // a call: the reply runs the result tx with the context it was queued with
  const describe = (target) => `document.querySelector("[data-describe='${target}']")`;
  await t.click({ text: `describe client ${id}`, selector: "button" });
  await t.waitFor(`${describe(id)}.innerText.includes("said: 1")`, { message: "call result" });
  const pongs = Number((await t.text(`[data-describe='${id}']`)).match(/pongs: (\d+)/)[1]);
  t.assert(pongs >= 1, "pongs reached the server");

  await t2.close();
  await t.waitFor(`document.querySelector("[data-client='${id2}']").innerText.includes("disconnected")`, { message: ":client-disconnect notify" });

  // same call, other outcome
  await t.click({ text: `describe client ${id2}`, selector: "button" });
  await t.waitFor(`${describe(id2)}.innerText.includes("the server doesn't know this client")`, { message: "failed call" });

  // losing the connection
  await t.click({ text: "server closes this socket", selector: "button" });
  await t.waitText("websocket not connected, reload the page to reconnect");
  await t.assertText("not connected");
  t.assert(await t.evaluate(`[...document.querySelectorAll("[data-demo] button")].every((b) => b.disabled)`), "buttons disabled");

  // a reload is a new connection
  await t.goto("/websocket/");
  await t.waitText("connected as client");
  t.assert(await idOf(t) !== id, "new client id");
  t.assert(!(await t.exists("#banner")), "banner gone");
};

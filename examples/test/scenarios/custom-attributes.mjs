// subtree context + custom attributes
export default async (t) => {
  await t.goto("/custom-attributes/");
  await t.waitText("registered:");
  const info = () => t.text("#info");
  const count = async () => { await t.click({ text: "count", selector: "button" }); return info(); };
  t.assert((await count()).includes("registered: 3, still in the document: 3"), "three items registered");

  let log = await t.logOf(() => t.click({ text: "change the first label", selector: "button" }));
  t.assert(log.includes('oval "one" nval "one!"'), `handler on change:\n${log}`);

  log = await t.logOf(() => t.click({ text: "remove the attribute from two", selector: "button" }));
  t.assert(log.includes('oval "two" nval nil'), "handler on removal of the attribute");
  t.assert((await info()).includes("registered: 2"), "unregistered");
  await t.click({ text: "add it back", selector: "button" });

  await t.click({ text: "highlight registered", selector: "button" });
  t.assert(await t.evaluate(`document.querySelectorAll("#group .group-highlight").length`) === 3, "group acts on its registered nodes");

  log = await t.logOf(() => t.click({ text: "remove the last element", selector: "button" }));
  t.assert(log.includes('::item handler, oval "three" nval nil'), `a destroyed fragment clears its namespaced attributes, the handler gets nval nil:\n${log}`);
  t.assert((await count()).includes("still in the document: 2"), "two elements left");
};

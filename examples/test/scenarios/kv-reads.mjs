// kv-lookup, kv-lookups, query, observation precision
export default async (t) => {
  await t.goto("/kv-reads/");
  await t.waitText("kv-lookup and kv-lookups");
  const r = (name) => t.renders(`[data-read='${name}']`);
  const names = ["entity-1", "entity-2", "path-1", "lookups", "table", "query-red", "query-blue", "query-first"];
  const snap = async () => Object.fromEntries(await Promise.all(names.map(async (n) => [n, await r(n)])));
  const changed = (a, b) => names.filter((n) => a[n] !== b[n]).sort().join(",");

  let s0 = await snap();
  await t.click({ text: "rename item 2", selector: "button" });
  let s1 = await snap();
  t.assert(changed(s0, s1) === "entity-2,lookups,query-blue,table", `rename item 2 reached: ${changed(s0, s1)}`);

  s0 = s1;
  await t.click({ text: "rename item 1", selector: "button" });
  s1 = await snap();
  t.assert(changed(s0, s1) === "entity-1,lookups,path-1,query-first,query-red,table", `rename item 1 reached: ${changed(s0, s1)}`);

  s0 = s1;
  await t.click({ text: "recolor item 3", selector: "button" });
  s1 = await snap();
  t.assert(changed(s0, s1) === "query-blue,query-red,table", `recolor item 3 reached: ${changed(s0, s1)}`);
  await t.assertText('(sg/query ?items-by-color :blue) → ["item 2!" "item 3"]');

  // a query calling another query fn directly is observed through the same env
  await t.assertText("(sg/query ?label-lengths :red) → [7]");
  await t.click({ text: "recolor item 3", selector: "button" });
  await t.waitText("(sg/query ?label-lengths :red) → [7 6]");
};

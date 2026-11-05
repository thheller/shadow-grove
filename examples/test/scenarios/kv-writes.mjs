// kv/add, kv/merge-seq, :joins, kv/set, kv/update-val, kv/remove-all; replacing a table
export default async (t) => {
  await t.goto("/kv-writes/");
  await t.waitFor(`!!document.querySelector("[data-table=person]")`);
  const table = (n) => t.text(`[data-table=${n}]`);
  const ids = () => t.text(`[data-ids]`);
  const click = (label) => t.click({ text: label, selector: "button" });

  await click("kv/add partial");
  let p = await table("person");
  t.assert(p.includes(':name "Ada (renamed)"') && p.includes('ada@example.com'), `kv/add merges: ${p}`);

  await click("kv/add with :joins");
  t.assert((await table("person")).includes(':address "a3"'), "nested map replaced by its primary key");
  t.assert((await table("address")).includes(':city "Austin"'), "nested map stored in the joined table");

  await click("kv/merge-seq to path");
  t.assert((await ids()).includes("[2 4]"), "ids written to the path");
  p = await table("person");
  t.assert(p.includes('"Grace (from list)"') && p.includes("grace@example.com"), "merge-seq merges too");
  await click("kv/merge-seq with fn");
  t.assert((await ids()).includes("[2 4 5]"), "ids passed to the fn");

  await click("kv/set");
  t.assert(!(await table("person")).includes("ada@example.com"), "kv/set replaces the entity");
  await click("kv/update-val");
  t.assert((await table("person")).includes('"Grace (from list)!"'), "kv/update-val");

  await click("kv/remove-all");
  t.assert((await table("person")) === "", "kv/remove-all");
  await click("replace table");
  p = await table("person");
  t.assert(p.includes("Alan") && p.includes("Ada") && !p.includes("Barbara"), "a plain map replaces the table");
  await click("book mon/1");
  await click("book mon/2");
  t.assert((await table("booking")).includes('["mon" 1]') && (await table("booking")).includes('["mon" 2]'), "composite keys");

  await click("replace table");
  const before = await table("person");
  t.expectConsoleError(/FAILED TO PROCESS EVENT|person ids must be numbers|no primary-key|IKVReduce|ExceptionInfo|Object/);
  await click("write rejected by :validate-fn");
  await click("kv/add without :primary-key");
  t.assert((await table("person")) === before, "failed txs commit nothing");
  t.assert(t.takeErrors().length >= 2, "both failures logged");
  await click("table nil");
  t.assert((await table("person")) === before, "setting a table to nil fails the tx, the table stays");
  t.assert(t.takeErrors().length >= 1, "the failure is logged");
};

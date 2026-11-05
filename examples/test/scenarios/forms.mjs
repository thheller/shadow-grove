// forms
export default async (t) => {
  await t.goto("/forms/");
  await t.waitText("controlled input backed by KV");
  await t.type("#search", "abc");
  await t.waitText("length 3");
  await t.click({ text: "clear from outside", selector: "button" });
  t.assert(await t.evaluate(`document.querySelector("#search").value`) === "", "KV overwrote the field");

  await t.click({ text: "add tag", selector: "button" });
  t.assert(await t.exists("#tag-error"), "validation error as data");
  await t.type("input[name=tag]", "x");
  await t.click({ text: "add tag", selector: "button" });
  await t.waitText('tags: ["x"]');
  t.assert(!(await t.exists("#tag-error")) && await t.evaluate(`document.querySelector("input[name=tag]").value`) === "", "submitted and reset");

  const name = () => t.evaluate(`document.querySelector("input[name=name]").value`);
  t.assert(await name() === "Ada", ":mount filled the field");
  const badge = () => t.renders({ text: "save ", selector: "form" });
  const b0 = await badge();
  await t.type("input[name=name]", " L");
  await t.waitText("save 1 change");
  t.assert(await badge() === b0 + 1, `the editor rendered once for the count change (${b0} -> ${await badge()})`);
  t.assert((await t.text("#saved")).includes(':name "Ada"'), "nothing in KV before save");
  await t.click("#save");
  await t.waitText('in KV: {:id 1, :name "Ada L"');
  t.assert(await t.evaluate(`document.querySelector("#save").disabled`), "no changes after save");
  await t.click({ text: "person 2", selector: "button" });
  await t.waitFor(`document.querySelector("input[name=name]")?.value === "Grace"`, { message: "^:stable remount filled the new person" });
};

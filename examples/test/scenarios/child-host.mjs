// hosting children, subtree env, ::sg/env-init
export default async (t) => {
  await t.goto("/child-host/");
  await t.waitText("a1");
  const dense = (row) => t.evaluate(`document.querySelector("[data-row='${row}']").dataset.dense`);
  t.assert(await dense("a1") === "false" && await dense("b1") === "true", "each frame's env reaches its children");
  t.assert(await dense("outside any frame") === "false", "outside a frame the default applies");
  await t.click({ text: "toggle frame a dense", selector: "button" });
  t.assert(await dense("a1") === "true" && await dense("a2") === "true" && await dense("b1") === "true", "env-watch on the frame atom");
  const color = () => t.evaluate(`getComputedStyle(document.querySelector("[data-row=a1]")).color`);
  t.assert(await color() === "rgb(29, 78, 216)", "app-wide env from ::sg/env-init");
  await t.click({ text: "toggle app accent (env-init)", selector: "button" });
  t.assert(await color() === "rgb(185, 28, 28)", "env-init atom watched");
};

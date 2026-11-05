// auth gate; session first, then history and render
export default async (t) => {
  await fetch(`${t.base}/auth-gate/api/reset`, { method: "POST" });
  await t.goto("/auth-gate/secret");
  await t.waitFor(`location.pathname === "/auth-gate/login"`, { message: "no session: redirected to /login" });
  await t.waitText("after login: /secret");

  await t.fill("input[name=username]", "demo");
  await t.fill("input[name=password]", "wrong");
  await t.click({ text: "log in", selector: "button" });
  await t.waitText("wrong username or password");

  await t.fill("input[name=password]", "demo");
  await t.click({ text: "log in", selector: "button" });
  await t.waitFor(`location.pathname === "/auth-gate/secret"`, { message: "back to the remembered token" });
  await t.waitText("only Demo User can read this");

  await t.goto("/auth-gate/home");
  await t.waitText("home page");
  await t.assertText("logged in as Demo User");

  await t.click({ text: "expire session on the server", selector: "button" });
  await t.sleep(500);
  await t.click({ text: "secret", selector: "nav a" });
  await t.waitFor(`location.pathname === "/auth-gate/login"`, { message: "403 logs out and redirects" });
  await t.assertNoText("logged in as");

  await t.fill("input[name=username]", "demo");
  await t.fill("input[name=password]", "demo");
  await t.click({ text: "log in", selector: "button" });
  await t.waitText("home page");
  await t.click({ text: "log out", selector: "button" });
  await t.waitFor(`location.pathname === "/auth-gate/login"`);
};

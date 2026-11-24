---
name: eval-cljs
description: Evaluate ClojureScript code in a connected runtime (browser page, node process, etc.) of a running shadow-cljs build via the `eval-cljs` bash tool. Use to inspect or call code in the running app, check app state, or try out CLJS code. For JVM-side code (build API, macros, build config) use eval-clj instead.
---

The `eval-cljs` bash tool compiles ClojureScript forms using the running shadow-cljs build worker, evaluates them in a connected JS runtime and prints the results as EDN. Forms are passed as command line args or piped via stdin.

Requirements:
- Run it from the project root. It reads `.shadow-cljs/http.port` relative to the current directory.
- The shadow-cljs server and build must already be running (e.g. `shadow-cljs watch app`), **and** a runtime must be connected (browser page open, or node process running). If `.shadow-cljs/http.port` is missing, ask the user to start shadow-cljs rather than spawning a second server yourself. Without a runtime you get `ERROR: There is no connected runtime.`
- To check active builds or start/compile a build on the JVM side, use the `eval-clj` skill.

```bash
❯ eval-cljs "(+ 1 2)"
;; RESULT ok
3

# Multiple forms: only the last result is printed by default.
# End with :ok when you don't care about a potentially huge return value.
❯ eval-cljs "(returns-some-large-value)" :ok
;; RESULT ok
:ok
```

Results larger than ~32KB are truncated and marked as `;; RESULT ok (truncated)`.

Prints made during evaluation (`println`, `prn`) are captured and shown as `;; OUTPUT` (browser `js/console.log` is **not** captured):

```bash
❯ eval-cljs "(+ 1 2)" "(println :yo)" :foo 1
;; OUTPUT
:yo
;; RESULT ok
1
```

# Picking the runtime

If exactly one CLJS runtime is connected, it is used automatically. If there are several (multiple browser tabs, multiple builds), the command fails and lists them:

```
ERROR: There are too many runtimes. Don't know where to eval. Pick one via --runtime=<id-from-below> or narrow by --build=<build-id>
| :runtime | :build | :type    | :description          |
|----------+--------+----------+-----------------------|
| 27       | :app   | :browser | user-agent=Mozilla... |
| 31       | :test  | :node    |                       |
```

- `--build=app` restricts to runtimes of that build (enough when that build has a single runtime).
- `--runtime=27` picks one specific runtime. Runtime ids change on page reload or process restart, so take them from the latest listing.

# Shell quoting

Use single quotes for forms containing `"`. For anything containing `'`, or spanning multiple lines, pipe a quoted heredoc to avoid shell escaping problems:

```bash
❯ eval-cljs <<'EOF'
(require 'my.app.util)
(my.app.util/format-price 1234 'EUR)
EOF
```

Files can be piped in directly too (`cat src/dev/scratch.cljs | eval-cljs`).

# Errors and warnings

Evaluation **stops at the first problem**, and remaining forms are not evaluated. **Note:** `eval-cljs` exits with status `0` even on errors or warnings (it only exits non-zero if the port file is missing or `curl` fails), so always inspect stdout rather than relying on exit codes or `&&` chaining.

- **Warnings**: code that compiles with warnings (e.g. an undeclared var) is **not evaluated at all**. You get the warning followed by `;; NOT EVALUATED: ...`. Fix the warning and try again.
- **Compile errors**: shown as `;; ERROR: CLJS Compilation failed` followed by the report.
- **Runtime errors**: shown as `;; ERROR` followed by the message. For `ex-info` errors the full error including `:data` is printed below it, e.g. `#error {:message "boom", :data {:a 1}}`.
- **Read errors** (e.g. unbalanced parens): shown as `;; ERROR: Failed to read input`. Forms before the read error are still evaluated.
- **Timeout**: each form has `10000`ms by default. Raise it with `--timeout=<ms>`.

`*e` holds the last exception, so `(ex-data *e)` or `(.-stack *e)` in a follow-up call can help.

# Async code

Use `(await ...)` in a top-level form to wait for a Promise and get its value as the result. Output printed while waiting is captured, and a rejected Promise is reported as a normal `;; ERROR`.

```bash
❯ eval-cljs '(-> (js/fetch "/api/data") (.then #(.json %)) (await) (js->clj :keywordize-keys true))'
;; RESULT ok
{:items [...]}

❯ eval-cljs '(let [res (await (js/fetch "/api/data"))] (.-status res))'
;; RESULT ok
200
```

- `await` works directly in the top-level form, including inside `let`, `do`, `->`, etc. It does **not** work inside a regular `fn`/`defn` body (`await can only be used in async contexts`). Mark the function `^:async` (`(defn ^:async load [] ...)`) and `(await (load))` it.
- Awaiting counts against `--timeout`. Raise it for slow requests.
- Without `await`, a Promise prints as `#object[Promise [object Promise]]`. Output from callbacks that run after evaluation finishes (`setTimeout`, event handlers, un-awaited `.then`) is **not** captured.

# CLI Options

Options start with `--` and **must use `=`** (e.g. `--timeout=60000`, never `--timeout 60000` — a space causes the value to be evaluated as a form). Put options before forms for readability.

- `--build=<build-id>` and `--runtime=<id>` — select the runtime (see above).
- `--timeout=<ms>` — per-form timeout (default `10000`).
- `--print=all` — print the result of every form, not just the last.
- `--ns=<ns>` — evaluate in `<ns>` instead of `cljs.user` (gives access to its private vars and aliases). Unlike `eval-clj`, this does **not** load the namespace; it must already be part of the build or `require`d first. Prefer `--ns` over `(in-ns '...)`.

```bash
❯ eval-cljs --ns=foo ::foo
;; RESULT ok
:foo/foo

❯ eval-cljs --print=all 1 2
;; RESULT ok
1
;; RESULT ok
2
```

# Best Practices

- While `shadow-cljs watch` is running, saved files are recompiled and hot-reloaded automatically, so manual reloading is usually unnecessary. To force a reload, use `(require 'my.app.util :reload)`.
- Prefer fully qualified names over aliases (`(require 'my.app.util)` + `my.app.util/fn`) to avoid alias conflicts in the shared `cljs.user` namespace.
- State lives in the runtime, so `def`s, `*1 *2 *3`, and `*e` persist between calls until the page is reloaded or the process restarts.
- Prefer returning values over `js/console.log`, since browser console output is not captured.

---
name: eval-clj
description: Evaluate Clojure code in the running shadow-cljs JVM via the `eval-clj` bash tool. Use for anything on the JVM side of a shadow-cljs project, such as the shadow.cljs.devtools.api build API (compile, watch, release), macros, build hooks, build config, or loading and testing .clj/.cljc files. For code running in a browser or node runtime use eval-cljs instead.
---

The `eval-clj` bash tool evaluates Clojure forms inside the **running shadow-cljs server JVM** and prints the results as EDN. Forms are passed as command line args or piped via stdin.

Requirements:
- Run it from the project root. It reads `.shadow-cljs/http.port` relative to the current directory.
- The shadow-cljs server must already be running. If `.shadow-cljs/http.port` is missing, ask the user to start it rather than spawning a second server yourself.

Since it runs inside the shadow-cljs server, `shadow.cljs.devtools.api` is available. Don't run anything that blocks forever, calls `System/exit`, or otherwise breaks the JVM, since that takes down the dev server for everyone.

```bash
❯ eval-clj "(+ 1 2)"
;; RESULT ok
3

# Multiple forms: only the last result is printed by default.
# End with :ok when you don't care about a potentially huge return value.
❯ eval-clj "(returns-some-large-value)" :ok
;; RESULT ok
:ok
```

Results larger than ~32KB are truncated and marked as `;; RESULT ok (truncated)`.

Output printed to `*out*`/`*err*` (including `clojure.test` output) is captured and shown as `;; OUTPUT` before the result of the form that printed it:

```bash
❯ eval-clj "(+ 1 2)" "(println :yo)" :foo 1
;; OUTPUT
:yo
;; RESULT ok
1
```

# Shell quoting

Use single quotes for forms containing `"`. For anything containing `'`, or spanning multiple lines, pipe a quoted heredoc to avoid shell escaping problems:

```bash
❯ eval-clj <<'EOF'
(require 'foo.bar :reload)
(foo.bar/thing "x" 'y)
EOF
```

Files can be piped in directly too (`cat src/dev/repl.clj | eval-clj`).

# Errors

Evaluation **stops at the first error**, and remaining forms are not evaluated. **Note:** `eval-clj` exits with status `0` even on evaluation errors (it only exits non-zero if the port file is missing or `curl` fails), so always inspect stdout rather than relying on exit codes or `&&` chaining.

```bash
❯ eval-clj '(/ 1 0)' :never
;; ERROR [id: 4]
Execution error (ArithmeticException) at user/eval61936 (REPL:1).
Divide by zero

;; TRACE
ArithmeticException: Divide by zero
    at ...

;; LAST FORM
(/ 1 0)
```

The trace is shortened and includes `ex-data` (as `data:`) and the `Caused by:` chain. Fetch the full exception object later by id via `(shadow.assist/get-error 4)`.

Read errors (e.g. unbalanced parens) are reported the same way; forms before the read error are still evaluated.

# CLI Options

Options start with `--` and **must use `=`** (e.g. `--timeout=60000`, never `--timeout 60000` — a space causes the value to be evaluated as a form). Put options before forms for readability.

- `--print=all` — print the result of every form instead of just the last.
- `--ns=<ns>` — evaluate in `<ns>` instead of `user` (gives access to its private vars and aliases). Loads the namespace via `require` if not yet loaded, but does **not** reload an already-loaded namespace. Prefer `--ns` over `(in-ns '...)`.
- `--timeout=<ms>` — per-call timeout (default `10000`). Raise it for test suites or release builds, e.g. `--timeout=120000`. Timed-out calls report `;; EVALUATION TIMED OUT after <ms>ms!` while still printing results of forms that finished.

```bash
❯ eval-clj --ns=foo ::foo
;; RESULT ok
:foo/foo

❯ eval-clj --print=all 1 2
;; RESULT ok
1
;; RESULT ok
2
```

# Common shadow-cljs API calls

```clojure
(shadow.cljs.devtools.api/get-build-ids)      ;; configured builds
(shadow.cljs.devtools.api/active-builds)      ;; currently running watch workers
(shadow.cljs.devtools.api/repl-runtimes :app) ;; connected CLJS runtimes for :app
(shadow.cljs.devtools.api/compile :app)       ;; one-off dev compile
(shadow.cljs.devtools.api/watch :app)         ;; start a watch worker
```

# Best Practices

- After editing a `.clj`/`.cljc` file, load it to check for errors:
  `eval-clj '(load-file "src/dev/build.clj")' :ok` or `eval-clj "(require 'a.namespace :reload)" :ok` (when on the classpath).
- Prefer fully qualified names over aliases (`(require 'foo.bar)` + `foo.bar/fn`) to avoid alias conflicts in the shared `user` namespace.
- Run tests with `eval-clj "(require 'foo.bar-test :reload)" "(clojure.test/run-tests 'foo.bar-test)"`.
- `*1 *2 *3` only refer to previous forms **within the same call** (reset on every call). Use `def` to persist values across calls.

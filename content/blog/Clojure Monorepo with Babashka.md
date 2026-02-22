---
tags:
  - clojure
  - babashka
  - architecture
  - devops
date: 2026-02-17
repos:
  - [lasagna-pattern, "https://github.com/flybot-sg/lasagna-pattern"]
rss-feeds:
  - all
  - clojure
---
## TLDR

A Babashka-driven approach to managing Clojure monorepos with auto-discovered components, two-layer task delegation, and consistent `deps.edn` conventions. Applied to [lasagna-pattern](https://github.com/flybot-sg/lasagna-pattern), a toolbox of 5 components spanning libraries and full-stack apps.

## Context

Our project [lasagna-pattern](https://github.com/flybot-sg/lasagna-pattern) is a monorepo hosting several Clojure components:

- `pattern`: core pattern-matching DSL (.cljc)
- `collection`: CRUD collection abstraction (.cljc)
- `remote`: HTTP transport layer (.clj)
- `examples/flybot-site`: full-stack blog with ClojureScript frontend
- `examples/pull-playground`: interactive SPA for learning pull patterns

The components have different needs: libraries just need RCT tests, while apps need nREPL, shadow-cljs, deployment tasks, and environment variables. We needed a build system that handles both without boilerplate.

## Why Babashka over deps.edn aliases

A common approach for Clojure monorepos is to manage everything through a single `deps.edn` with namespaced aliases like `:server/dev`, `:client/test`, `:mobile/ios`. It works for small projects, but breaks down as components multiply:

- **Single deps.edn grows unwieldy**: aliases for every component in one file
- **No component isolation**: every REPL loads the root deps, even deps only needed by one component
- **Manual alias orchestration**: you have to remember which aliases to combine (`:jvm-base` + `:server/dev` + `:build`)
- **No way to override per component**: if one component needs a different test strategy, you add yet another alias

The Babashka approach inverts this: each component owns its own `deps.edn`, and a root task runner orchestrates them.

## Repo structure

```
lasagna-pattern/
├── bb.edn              # Root task runner
├── pattern/
│   ├── deps.edn        # Own deps, own aliases
│   └── bb.edn          # Override: test runs RCT only
├── collection/
│   ├── deps.edn
│   └── bb.edn
├── remote/
│   └── deps.edn        # No bb.edn, uses root defaults
└── examples/
    ├── flybot-site/
    │   ├── deps.edn
    │   ├── bb.edn      # Override: dev, test, deploy, serve
    │   └── .env
    └── pull-playground/
        ├── deps.edn
        └── bb.edn
```

The key insight: **adding a component is just adding a directory with a `deps.edn`**. No registration, no configuration in the root. Run `bb list` and it appears.

The root-level `bb.edn` approach was [@Robert Luo](https://github.com/robertluo)'s idea. I extended it with the two-layer delegation pattern (component-level `bb.edn` overrides) and uniform `deps.edn` alias conventions to keep things predictable as components grew.

## Auto-discovery

The root `bb.edn` discovers components by scanning for directories containing a `deps.edn`:

```clojure
-components
{:task (vec (concat
              ;; Top-level: pattern/, collection/, remote/
              (for [dir (fs/list-dir ".")
                    :let [dir-name (str (fs/file-name dir))]
                    :when (and (fs/directory? dir)
                               (not (str/starts-with? dir-name "."))
                               (not= dir-name "examples")
                               (fs/exists? (fs/file dir "deps.edn")))]
                dir-name)
              ;; Nested: examples/flybot-site/, examples/pull-playground/
              (when (fs/exists? "examples")
                (for [dir (fs/list-dir "examples")
                      :let [path (str "examples/" (fs/file-name dir))]
                      :when (and (fs/directory? dir)
                                 (fs/exists? (fs/file path "deps.edn")))]
                  path))))}
```

This gives us:

```bash
$ bb list
pattern
collection
remote
examples/flybot-site
examples/pull-playground
```

## Two-layer task delegation

The root defines common tasks (`test`, `rct`, `dev`, `clean`, `nrepl`). For each, it checks if the component has a local `bb.edn` override. If yes, delegate. If not, run the default.

The check uses `bb tasks` output rather than parsing `bb.edn` directly, which is more robust against syntax variations:

```clojure
-comp-task?
{:task (fn [dir task]
         (let [{:keys [out]} (shell {:dir dir :out :string :continue true}
                                    "bb" "tasks")
               pattern (re-pattern (str "^" task "\\s"))]
           (some (fn [line] (re-find pattern line))
                 (str/split-lines out))))}
```

Then each task follows the same pattern:

```clojure
test
{:doc "Run tests (kaocha): bb test [component]"
 :depends [-components -comp-task?]
 :task (let [target (first *command-line-args*)
             components (if target [target] -components)]
         (doseq [c components]
           (println (str "\n=== " c " ==="))
           (if (-comp-task? c "test")
             (shell {:dir c} "bb" "test")
             (clojure {:dir c} "-M:dev:kaocha"))))}
```

Run all tests: `bb test`. Run one component: `bb test pattern`. The root does not care how each component tests itself.

## deps.edn alias convention

All components follow the same alias names so the root defaults work:

| Alias | Purpose | Invoked by |
|-------|---------|------------|
| `:dev` | Dev deps (RCT, nrepl, cider) | `bb dev <comp>` |
| `:rct` | RCT exec-fn | `bb rct` (default) |
| `:kaocha` | Kaocha main-opts | `bb test` (default) |

A minimal component `deps.edn`:

```clojure
{:paths ["src"]
 :deps {org.clojure/clojure {:mvn/version "1.12.4"}}
 :aliases
 {:dev {:extra-paths ["notebook"]
        :extra-deps {io.github.robertluo/rich-comment-tests
                     {:mvn/version "1.1.78"}}}
  :rct {:exec-fn com.mjdowney.rich-comment-tests.test-runner/run-tests-in-file-tree!
        :exec-args {:dirs #{"src"}}}}}
```

No kaocha alias needed if the component only uses RCT. The local `bb.edn` overrides `test` to run RCT directly:

```clojure
;; pattern/bb.edn
{:tasks
 {test
  {:doc "Run RCT (no kaocha needed)"
   :task (clojure "-X:dev:rct")}}}
```

## Component overrides for apps

Apps like `flybot-site` need more than just testing. Their local `bb.edn` defines the full lifecycle:

```clojure
;; examples/flybot-site/bb.edn
{:tasks
 {dev
  {:doc "Start nREPL with shadow-cljs middleware"
   :task (clojure {:env (current-env)}
           "-M:dev:cljs -m nrepl.cmdline
            --middleware \"[shadow.cljs.devtools.server.nrepl/middleware
                           cider.nrepl/cider-middleware]\"")}
  test
  {:doc "Run tests (kaocha includes RCT)"
   :task (apply clojure "-M:dev:kaocha" *command-line-args*)}

  serve
  {:doc "Build and serve release frontend"
   :task (do (shell "npm install")
             (clojure "-M:cljs -m shadow.cljs.devtools.cli release app")
             ;; ... render index-template.html with hashed assets
             (shell "npx http-server resources/public -p 3000 -c-1"))}

  deploy
  {:doc "Build and push container image to ECR"
   :depends [clean]
   :task (do (shell "npm install")
             (clojure "-M:cljs -m shadow.cljs.devtools.cli release app")
             (clojure "-T:jib build"))}}}
```

The root `bb dev examples/flybot-site` delegates to this, loading `.env` automatically:

```clojure
dev
{:doc "Start REPL: bb dev <component>"
 :depends [-comp-task? -load-env]
 :task (if-let [component (first *command-line-args*)]
         (let [env (-load-env component)]
           (if (-comp-task? component "dev")
             (shell {:dir component :env env} "bb" "dev")
             (clojure {:dir component :env env} "-M:dev")))
         (println "Usage: bb dev <component>"))}
```

## Local dependencies

Components depend on each other via `:local/root`:

```clojure
;; collection/deps.edn
{:deps {local/pattern {:local/root "../pattern"}}}

;; remote/deps.edn
{:deps {local/pattern    {:local/root "../pattern"}
        local/collection {:local/root "../collection"}}}
```

Changes in `pattern/src` are picked up immediately when developing `collection` or `remote`. No publishing step.

## Release tagging

Deployment is triggered by git tags. The root `bb.edn` has a `tag` task that reads `resources/version.edn` from the component and creates a tag:

```bash
$ bb tag examples/pull-playground
Creating tag: pull-playground-v0.3.0
Pushing tag to origin...
Done! CI/CD will deploy examples/pull-playground with tag pull-playground-v0.3.0
```

CI picks up the tag pattern and deploys:

| Component | Tag pattern | Target |
|-----------|-------------|--------|
| `pull-playground` | `pull-playground-v*` | S3 + CloudFront |
| `flybot-site` | `flybot-site-v*` | ECR + App Runner |

## Adding a new component

1. Create the directory with a `deps.edn`
2. Optionally add a local `bb.edn` for custom tasks
3. Run `bb list` to confirm discovery

That is it. No root configuration to update, no registration step. The convention (consistent aliases, `bb tasks` delegation) makes everything work automatically.

## Conclusion

The single `deps.edn` approach works for small projects, but it does not scale to a monorepo with mixed component types. Babashka task delegation gives us:

- **Zero-config component discovery**: just add a directory with `deps.edn`
- **Sensible defaults with per-component overrides**: libraries get RCT, apps get custom lifecycles
- **Uniform interface**: `bb test`, `bb dev`, `bb clean` work the same everywhere
- **Local dependencies**: changes propagate immediately, no publish step

The full implementation is in [lasagna-pattern/bb.edn](https://github.com/flybot-sg/lasagna-pattern/blob/main/bb.edn).
# Architecture

## dispatch-of (effects-as-data)

Custom dispatch pattern (NOT Replicant's built-in action system). Components close over `dispatch!` and call it directly with effect maps:

```clojure
(dispatch! {:db      (fn [d] (db/select-post d slug))
            :history :push})
```

Effects execute in deterministic order: `:db` first, then `:history`.

| Effect | Value | What happens |
|--------|-------|--------------|
| `:db` | `(fn [db] db')` | `swap! app-db update root-key f` |
| `:history` | `:push` | `pushState` URL from current state |

## DB layer

Pure `db -> db` updater functions in `db.cljc`. Testable on JVM. All state lives under `:app/me` in the app-db atom.

## Faceted tag filtering

Tags are split into two categories defined in `config.edn` `:project-tags`: project tags (e.g. `lasagna-pattern`, `hibou`) and topic tags (everything else). The tag bar renders two rows with distinct styling (amber for projects, blue for topics).

Filtering uses AND logic with a set of active tags (`:tag-filters #{}`). `toggle-tag` adds/removes a tag from the set. `filtered-posts` returns posts matching ALL selected tags. URLs encode filters as sorted `+`-separated tags: `/tags/analytics+hibou`. Legacy `/tag/x` URLs still parse correctly.

## Shared utilities

`util.cljc` contains cross-cutting text functions (`slugify`, `strip-inline-md`) used by build, UI, and import namespaces. This avoids `build/ -> ui/` cross-module dependencies.

## Site configuration

`config.edn` at project root holds site metadata (author, URLs, RSS feed config). Read at build/macro-expansion time by `config.clj`. Site metadata is embedded in the CLJS bundle via `(config/site-config)` macro in `db.cljc`. Views read it from `(:site db)`.

## Content pipeline

Blog posts live in `content/blog/` as markdown with YAML frontmatter. Media lives in `content/media/`. The `posts-data` macro in `md.clj` loads posts at compile time: parses frontmatter (clj-yaml), extracts TLDR, validates with Malli, and embeds in the JS bundle.

Markdown is rendered at runtime in the browser (see `ui/core/markdown.cljc`) via marked + highlight.js, with `mermaid` code blocks rendered as diagrams. mermaid is self-hosted and loaded lazily via a `<script>` tag only on pages that contain a diagram, because bundling it would add 4.4MB to `main.js` on every page. `bb js-deps` copies the mermaid bundle from `node_modules` into the gitignored `resources/public/lib/`. `bb dist` then copies that directory into `dist/`. `bb dev`, `bb watch` and `bb build` run `js-deps` first. Diagrams follow the live light/dark theme via a `MutationObserver` on `data-theme`.

Because posts are embedded at compile time, changing markdown does not hot-reload on its own. When a shadow watch is running, recompile the macro's namespace (`touch src/loicb/me/ui/core/db.cljc`) to re-expand `posts-data` and pick up the new content in the browser.

`bb import-notes` copies files to `content/`, normalizes markdown (strips internal refs, converts `[[wiki links]]` to web links, rewrites `../media/` paths to `/assets/media/`), then runs `copy-assets` so imported media is published to `resources/public/assets/media/` and renders in dev.

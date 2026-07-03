# Portfolio Site (me)

Personal portfolio SPA at [loicb.dev](https://www.loicb.dev). ClojureScript + Replicant + shadow-cljs. No server — all content embedded at compile time via macros.

## Quick Start

```bash
bb dev                # nREPL with CLJ + CLJS support
```

```clojure
;; In REPL: start shadow-cljs (same JVM)
(start!)              ; shadow-cljs server + watch :app on port 3000
(stop!)               ; stop shadow-cljs
(cljs-repl!)          ; connect to browser CLJS REPL
:cljs/quit            ; exit CLJS REPL back to CLJ
```

## Architecture

### dispatch-of (effects-as-data)

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

### DB layer

Pure `db -> db` updater functions in `db.cljc`. Testable on JVM. All state lives under `:app/me` in the app-db atom.

### Faceted tag filtering

Tags are split into two categories defined in `config.edn` `:project-tags`: project tags (e.g. `lasagna-pattern`, `hibou`) and topic tags (everything else). The tag bar renders two rows with distinct styling (amber for projects, blue for topics).

Filtering uses AND logic with a set of active tags (`:tag-filters #{}`). `toggle-tag` adds/removes a tag from the set. `filtered-posts` returns posts matching ALL selected tags. URLs encode filters as sorted `+`-separated tags: `/tags/analytics+hibou`. Legacy `/tag/x` URLs still parse correctly.

### Shared utilities

`util.cljc` contains cross-cutting text functions (`slugify`, `strip-inline-md`) used by build, UI, and import namespaces. This avoids `build/ -> ui/` cross-module dependencies.

### Site configuration

`config.edn` at project root holds site metadata (author, URLs, RSS feed config). Read at build/macro-expansion time by `config.clj`. Site metadata is embedded in the CLJS bundle via `(config/site-config)` macro in `db.cljc`. Views read it from `(:site db)`.

### Content pipeline

Blog posts live in `content/blog/` as markdown with YAML frontmatter. Media lives in `content/media/`. The `posts-data` macro in `md.clj` loads posts at compile time: parses frontmatter (clj-yaml), extracts TLDR, validates with Malli, and embeds in the JS bundle. Markdown is rendered at runtime in the browser (see `ui/core/markdown.cljc`) via marked + highlight.js, with `mermaid` code blocks rendered as diagrams. mermaid is self-hosted (`resources/public/vendor/mermaid.min.js`, copied to `dist/` by `bb dist`) and loaded lazily via a `<script>` tag only on pages that contain a diagram, since shadow-cljs cannot bundle its ESM. Diagrams follow the live light/dark theme via a `MutationObserver` on `data-theme`.

#### Importing from Obsidian

```bash
bb import-notes /path/to/vault/Articles
```

The articles directory must contain:
- `blog/` (required) — markdown posts with YAML frontmatter
- `media/` (optional) — images referenced by posts

The task copies files to `content/`, normalizes markdown (strips internal refs, converts `[[wiki links]]` to web links, rewrites `../media/` paths to `/assets/media/`), and finally runs `copy-assets` so imported media is published to `resources/public/assets/media/` and renders in dev. If you don't know the user's articles path, ask them.

Because posts are embedded at compile time, changing markdown does not hot-reload on its own. When a shadow watch is running, recompile the macro's namespace (`touch src/loicb/me/ui/core/db.cljc`) to re-expand `posts-data` and pick up the new content in the browser.

### Build & deploy

`bb dist` produces a `dist/` directory ready for static hosting (Netlify). The pipeline: `bb build` (shadow-cljs release with content hashed filenames) + `bb rss` (RSS feeds via commonmark-java) + `bb copy-assets` (media to public). The dist task reads `resources/index-template.html`, injects the hashed JS filename, and gathers all static files.

Netlify config (`netlify.toml`): SPA fallback redirect + Image CDN for media optimization.

## Testing

Rich Comment Tests (RCT). All source files are `.cljc` — tests run on JVM, no browser needed:

```bash
bb test
```

Every new db updater, history function, or build helper needs an RCT test directly below it.

## Key Files

```
config.edn                    # Site metadata, RSS feed config, project-tags
build.clj                     # tools.build entry (RSS generation)
src/loicb/me/
├── config.clj                # Reads config.edn, site-config macro
├── util.cljc                 # Shared utilities (slugify, strip-inline-md)
├── build/
│   ├── md.clj                # Markdown loader, posts-data macro, Malli schema
│   ├── rss.clj               # RSS feed generator (commonmark-java)
│   └── import.clj            # Obsidian -> content/ normalization
└── ui/
    ├── core.cljc             # Entry point, dispatch-of, rendering, init
    └── core/
        ├── db.cljc           # Pure db updaters, initial-db, TOC, faceted filtering
        ├── history.cljc      # URL routing (state <-> path, multi-tag URLs)
        ├── markdown.cljc     # Runtime markdown rendering (marked + highlight.js + mermaid)
        └── views.cljc        # Replicant defalias components
dev/
└── user.clj                  # REPL helpers: start!, stop!, cljs-repl!
```

## bb tasks

| Task | Purpose |
|------|---------|
| `bb dev` | nREPL with shadow-cljs middleware |
| `bb watch` | shadow-cljs watch (standalone, no REPL) |
| `bb test` | Run RCT tests on JVM |
| `bb import-notes <dir>` | Import articles from vault to `content/` (also runs `copy-assets`) |
| `bb copy-assets` | Copy `content/media` to public assets |
| `bb build` | Release JS bundle (depends on copy-assets) |
| `bb rss` | Generate RSS feeds |
| `bb dist` | Full build + gather static files for deploy |
| `bb clean` | Remove build artifacts |
| `bb fmt-check` / `bb fmt-fix` | Code formatting |
| `bb outdated` | Check dependency versions |

## Commit conventions

Format: `<prefix>(<scope>): <description>` (imperative mood, single line).

Prefixes: `feat`, `fix`, `refactor`, `docs`, `test`, `chore`.

| Scope | When |
|-------|------|
| `app` | SPA code, build pipeline, config, CSS, infra |
| `blog` | Blog content imported from Obsidian |

Examples:
```
feat(app): add tag filter to home view
fix(app): handle unknown routes in history
chore(app): bump shadow-cljs to 3.4.0
feat(blog): import Clojure concurrency article
```

## deps.edn aliases

| Alias | Purpose |
|-------|---------|
| `:dev` | nREPL + CIDER + kaocha + RCT |
| `:cljs` | shadow-cljs |
| `:rct` | RCT test runner (standalone, no dev deps) |
| `:build` | tools.build for RSS generation |
| `:kaocha` | Kaocha test runner |
| `:cljfmt` | Code formatting |
| `:outdated` | Dependency checker |

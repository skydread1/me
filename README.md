<h1 align="center">
    <a href="https://www.loicb.dev/" target="_blank" rel="noopener noreferrer"><img src="./resources/public/logo.png" alt="loicb.dev" width="28" height="28" style="vertical-align: middle;"></a>
    www.loicb.dev
</h1>

<div align="center">
    <a href="https://www.loicb.dev/"><img src="https://img.shields.io/badge/www.loicb.dev-live-blue" alt="Website"></a>
    <a href="https://clojure.org/"><img src="https://img.shields.io/badge/clojure-v1.12.4-blue.svg" alt="Clojure"></a>
    <a href="https://clojurescript.org/"><img src="https://img.shields.io/badge/clojurescript-v1.12.42-blue.svg" alt="ClojureScript"></a>
    <a href="https://github.com/skydread1/me/actions/workflows/ci.yml"><img src="https://github.com/skydread1/me/actions/workflows/ci.yml/badge.svg" alt="Test"></a>
    <a href="https://github.com/skydread1/me/actions/workflows/deploy.yml"><img src="https://github.com/skydread1/me/actions/workflows/deploy.yml/badge.svg" alt="Deploy"></a>
    <a href="https://github.com/skydread1/me/blob/main/LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue.svg" alt="License"></a>
</div>

<br>

Personal portfolio and blog. Built with ClojureScript, [Replicant](https://github.com/cjohansen/replicant), and shadow-cljs.

Static SPA — no server. Blog content is embedded at compile time. Deployed to [Netlify](https://www.netlify.com/).

## Stack

| Layer | Technology |
|-------|------------|
| UI | [Replicant](https://github.com/cjohansen/replicant) (hiccup-based, no virtual DOM) |
| Build | [shadow-cljs](https://github.com/thheller/shadow-cljs) via [Babashka](https://babashka.org/) tasks |
| Markdown | [marked](https://github.com/markedjs/marked) + [highlight.js](https://highlightjs.org/) (browser-side) |
| Validation | [Malli](https://github.com/metosin/malli) (compile-time schema checks) |
| RSS | [commonmark-java](https://github.com/commonmark/commonmark-java) + [clj-rss](https://github.com/yogthos/clj-rss) |
| Config | EDN ([clj-yaml](https://github.com/clj-commons/clj-yaml) for frontmatter) |
| Hosting | [Netlify](https://www.netlify.com/) with Image CDN |

## Prerequisites

- [Java](https://adoptium.net/) 21+
- [Clojure CLI](https://clojure.org/guides/install_clojure)
- [Babashka](https://github.com/babashka/babashka#installation)
- [Node.js](https://nodejs.org/) 18+

## Development

```bash
bb dev
```

This starts an nREPL with shadow-cljs middleware. Connect your editor, then in the REPL:

```clojure
(start!)        ; starts shadow-cljs server + watches :app build
                ; dev server at http://localhost:3000
```

Other REPL helpers: `(stop!)`, `(cljs-repl!)`, `:cljs/quit`.

For a standalone dev server without REPL:

```bash
bb watch        ; shadow-cljs watch on port 3000
```

## Content

Blog posts are markdown files with YAML frontmatter in `content/blog/`. Media (images) lives in `content/media/`.

### Importing from Obsidian

```bash
bb import-notes /path/to/vault/Articles
```

The articles directory should contain:
- `blog/` — markdown files with YAML frontmatter
- `media/` (optional) — images referenced by posts

The import normalizes Obsidian-flavored markdown: converts `[[wiki links]]` to standard web links, rewrites relative media paths, and strips vault-internal sections.

### Frontmatter format

```yaml
---
date: 2026-02-20
tags:
  - clojure
  - architecture
rss-feeds:
  - clojure
  - all
repos:
  - [repo-name, "https://github.com/..."]
---
```

The filename becomes the post title and URL slug (e.g. `Getting Started with Replicant.md` becomes `/blog/getting-started-with-replicant`).

## Testing

```bash
bb test
```

Uses [Rich Comment Tests](https://github.com/RobertLuo1/rich-comment-tests) (RCT). All source files are `.cljc` so tests run on the JVM without a browser.

## Build & Deploy

```bash
bb dist
```

Produces a `dist/` directory with all static files:
- `index.html` with cache-busted JS reference
- Optimized JS bundle (`:advanced` compilation)
- CSS, media assets, RSS feeds

### Deploy pipeline

`bb dist` runs: `bb build` (shadow-cljs release + asset copy) -> `bb rss` (feed generation) -> static file assembly.

Netlify serves the `dist/` directory. Config in `netlify.toml` handles SPA routing (fallback to `index.html`) and Image CDN optimization for media.

## Project Structure

```
config.edn                    Site metadata, RSS feed config
build.clj                     tools.build entry point
bb.edn                        Babashka task definitions
shadow-cljs.edn               shadow-cljs build config
netlify.toml                  Netlify deploy + image CDN config

content/
├── blog/                     Markdown posts (YAML frontmatter)
└── media/                    Images referenced by posts

src/loicb/me/
├── config.clj                Reads config.edn, provides site-config macro
├── build/
│   ├── md.clj                Post loader, posts-data macro, Malli schema
│   ├── rss.clj               RSS feed generator
│   └── import.clj            Obsidian markdown normalization
└── ui/
    ├── core.cljc             Entry point, dispatch-of, rendering
    └── core/
        ├── db.cljc           Pure state updaters (db -> db)
        ├── history.cljc      URL routing (state <-> path)
        └── views.cljc        Replicant components (defalias)

dev/
└── user.clj                  REPL helpers
```

## All Tasks

| Task | Description |
|------|-------------|
| `bb dev` | Start nREPL with shadow-cljs middleware |
| `bb watch` | Start shadow-cljs dev server (standalone) |
| `bb test` | Run RCT tests on JVM |
| `bb import-notes <dir>` | Import articles from Obsidian vault |
| `bb copy-assets` | Copy media to public serving directory |
| `bb build` | Compile optimized JS bundle |
| `bb rss` | Generate RSS feeds |
| `bb dist` | Full production build |
| `bb clean` | Remove all build artifacts |
| `bb fmt-check` | Check code formatting |
| `bb fmt-fix` | Fix code formatting |
| `bb outdated` | Show outdated dependencies |

## License

See [LICENSE](LICENSE).

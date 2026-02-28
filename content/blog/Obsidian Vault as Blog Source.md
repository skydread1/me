---
tags:
  - obsidian
  - architecture
  - web
date: 2026-02-19
rss-feeds:
  - all
---
## TLDR

Using an Obsidian vault as the single source of truth for blog content. Articles live alongside the working notes that informed them, linked through wiki links. The blog site is just a renderer, markdown files are the contract, and the entire authoring workflow happens inside the vault.

## Context

Most blog setups separate content from the knowledge that produced it. You research in one tool, draft in another, publish through a third. The blog posts float in isolation, with no connection to the investigation notes, design sketches, or code experiments that made them possible.

My blog at [loicb.dev](https://loicb.dev) has some articles covering Clojure development, AWS deployments, Rama analytics, and security practices. Every one of these articles started as notes: debugging sessions, architecture decisions, project retrospectives. The articles are the polished output, but the notes are the working knowledge.

I wanted both to live in the same place, linked to each other, visible in the same graph. The blog site should consume the markdown and render it. Nothing more.

## Vault structure

The vault has two content directories that matter for publishing:

```
dev-notes/
├── Articles/
│   ├── blog/          # Published article sources
│   ├── about/         # About page content
│   └── media/         # Images organized by article topic
├── Notes/             # Technical notes and learnings (never deployed)
└── CLAUDE.md          # Vault conventions
```

`Articles/blog/` contains the markdown files that become blog posts. These are public-facing, so they are reviewed for sensitive content before publication (no internal URLs, credentials, or client-specific details). `Notes/` contains working knowledge: quick references, gotchas, investigation results, framework-specific patterns. Notes are never deployed.

Articles and notes are both just markdown files in the same Obsidian vault. They appear in the same graph view, the same search results, the same backlinks panel. The only difference is their directory, which determines whether they get deployed to the website.

## Articles as connected notes

Every article ends with an `## Internal refs` section containing wiki links to the vault notes that informed it:

```markdown
## Frontmatter as schema

Every article uses YAML frontmatter that doubles as a schema for the website's build system:

```yaml
---
tags:
  - clojure
  - aws
  - devops
  - docker
date: 2026-02-17
repos:
  - [lasagna-pattern, "https://github.com/flybot-sg/lasagna-pattern"]
---
```

| Field | Purpose in vault | Purpose on website |
|-------|-----------------|-------------------|
| `tags` | Obsidian search, filtering | Tag-based filtering on the home page |
| `date` | Chronological context | Sort order, display date |
| `repos` | Quick reference to related code | GitHub links on the article page |

The current portfolio site validates frontmatter at compile time with a [Malli](https://github.com/metosin/malli) schema. If a required field is missing or malformed, the build fails. This catches errors before deployment rather than rendering broken pages.

Tags follow a consistent taxonomy documented in the vault's `CLAUDE.md`:

- **Languages**: `clojure`, `java`, `rust`, `python`, `javascript`
- **Platforms**: `rama`, `kafka`, `docker`, `k8s`, `aws`
- **Concepts**: `architecture`, `functional-programming`, `security`
- **Tools**: `git`, `obsidian`, `babashka`

The taxonomy lives in one place and applies to both articles and notes. When I add a tag to the taxonomy, it is immediately available for both content types.

## TLDR extraction

Every article opens with a `## TLDR` section, one to three sentences summarizing the article. The website extracts this section at build time and uses it for:

- **Vignettes** on the home page (article previews)
- **RSS feed** descriptions
- **Meta descriptions** for SEO

The extraction is a simple regex that strips the `## TLDR` heading and captures everything until the next heading. One piece of content, three rendering contexts. The author writes the TLDR once in the markdown file; the build system does the rest.

```clojure
(defn extract-tldr [markdown-content]
  ;; Strips ## TLDR section, stores as :post/md-content-short
  ;; Used for vignettes, RSS, and meta descriptions
  ...)
```

This is a deliberate design choice: the article is self-contained. All the metadata the website needs is in the markdown file itself, frontmatter for structured data, TLDR for the summary, body for the full content. No external CMS, no database, no API.

## Markdown as the contract

The architecture is intentionally simple:

```
┌──────────────────┐         ┌──────────────────┐
│  Obsidian Vault  │         │  Portfolio Site   │
│                  │  build  │                   │
│  Articles/blog/  │────────▶│  Rendered HTML    │
│  *.md files      │         │  (ClojureScript)  │
│                  │         │                   │
│  Notes/          │         │  Strips:          │
│  (not deployed)  │         │  - Internal refs  │
│                  │         │  Converts:        │
│                  │         │  - Wiki links     │
│  Media/          │         │                   │
│  (not deployed)  │         │                   │
└──────────────────┘         └──────────────────┘
```

The website reads the markdown files from `Articles/blog/`, parses frontmatter, extracts TLDRs, and embeds the raw markdown in the JS bundle at compile time. At runtime, markdown is rendered in the browser using marked.js and highlight.js. Internal refs are stripped during the build, and wiki links are converted to regular links so they work in the browser.

This separation means:

- **Authoring** happens entirely in Obsidian (or via Claude Code)
- **The website has no CMS**, it is a static SPA with all content embedded at compile time
- **Content and presentation are decoupled**, I can redesign the site without touching any article
- **The vault is portable**, if I switched from ClojureScript to Hugo or Astro tomorrow, the markdown would work unchanged

## The portfolio site

The portfolio site is a ClojureScript SPA built with [Replicant](https://github.com/cjohansen/replicant) and [shadow-cljs](https://github.com/thheller/shadow-cljs), hosted on Netlify. It is open source: [github.com/skydread1/me](https://github.com/skydread1/me).

The content pipeline is straightforward. A Babashka task imports articles from the vault into the project, normalizing markdown along the way (stripping internal refs, converting wiki links, rewriting media paths). At compile time, macros load the markdown, parse frontmatter, validate it with Malli, and embed everything in the JS bundle. No server, no CMS, no database.


## Conclusion

- **One vault, two purposes**, working notes and published articles live side by side, connected through wiki links
- **Internal refs preserve the research trail**, every article links back to the notes that informed it, visible in the vault but stripped for deployment
- **Frontmatter is the schema**, tags, date, and repos serve both Obsidian and the website build system
- **TLDR extraction** gives one piece of content three rendering contexts (vignettes, RSS, meta descriptions)
- **Markdown is the contract**, the website is a renderer, not a CMS. Content and presentation are fully decoupled
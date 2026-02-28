---
tags:
  - clojure
  - clojurescript
  - architecture
  - web
date: 2026-02-17
repos:
  - [lasagna-pattern, "https://github.com/flybot-sg/lasagna-pattern"]
rss-feeds:
  - all
---
## TLDR

[flybot.sg](https://www.flybot.sg/) is a full-stack Clojure web app built to demonstrate the "lasagna stack": a pull-based pattern language where the same declarative patterns handle reads, writes, authorization, and client-server transport. This article is a project overview linking to the detailed articles on each aspect.

## Context

Most web applications scatter data access logic across controllers, services, and ORM layers. The [lasagna-pattern](https://github.com/flybot-sg/lasagna-pattern) toolbox, designed by [@Robert Luo](https://github.com/robertluo), takes a different approach: a single pattern language that works across the entire stack. Patterns are EDN data (not strings, not macros) and they express both the shape of data you want and the mutations you need.

[flybot.sg](https://www.flybot.sg/) is the proof that this approach works in production. It is an open-source company blog where employees write and manage posts using Markdown, with role-based access control, Google OAuth, post history, and image uploads. The entire application (backend, frontend, and shared code) lives in the [lasagna-pattern](https://github.com/flybot-sg/lasagna-pattern) monorepo as an example component.

Robert designed and implemented the three core components (`pattern`, `collection`, `remote`), evolving from an earlier pull-pattern library he had been thinking about for years. The example applications (flybot-site, pull-playground) were built separately. Integrating fun-map lifecycle, Replicant rendering, and the custom `dispatch-of` effect system required significant manual work as these libraries are not widely used enough for AI-assisted coding to handle reliably.

## Architecture

```
src/sg/flybot/flybot_site/
├── server/
│   ├── system.clj              # fun-map lifecycle
│   └── system/
│       ├── api.clj             # Role-based API schema + collections
│       ├── auth.clj            # Google OAuth + role enforcement
│       ├── db.cljc             # Datahike connection + schema
│       ├── db/
│       │   ├── post.cljc       # PostsDataSource
│       │   ├── user.cljc       # UsersDataSource
│       │   └── role.cljc       # UserRolesDataSource
│       └── s3.clj              # Image upload handler
└── ui/
    ├── core.cljc               # Entry point, dispatch-of, transit
    └── core/
        ├── db.cljc             # Pure state updaters
        ├── pull.cljc           # Pull spec definitions (pattern + :then)
        ├── views.cljc          # Replicant defalias components
        └── history.cljc        # URL <-> state mapping
```

The backend serves a Ring handler with the pull-based API at `/api`. The frontend is a ClojureScript SPA compiled by shadow-cljs, rendered with Replicant. The two communicate via Transit-encoded pull patterns over HTTP.

## Stack

| Layer | Library | Why |
|-------|---------|-----|
| Pattern matching | [lasagna-pattern](https://github.com/flybot-sg/lasagna-pattern) | Core value prop: declarative patterns for reads and writes |
| Dependency injection | [fun-map](https://github.com/robertluo/fun-map) | Associative DI with lazy initialization and lifecycle management |
| Database | [Datahike](https://github.com/replikativ/datahike) | Datalog with history, runs embedded (no separate process), S3-backed in prod |
| Frontend rendering | [Replicant](https://github.com/cjohansen/replicant) | Lightweight hiccup rendering with `defalias`, no framework overhead |
| Frontend build | [shadow-cljs](https://github.com/thheller/shadow-cljs) | npm integration, hot reload, hashed release builds |
| HTTP server | [http-kit](https://github.com/http-kit/http-kit) | Simple, performant Ring-compatible server |
| Auth | [ring-oauth2](https://github.com/studer-l/ring-oauth2) | Standard Ring middleware for Google OAuth |
| Validation | [Malli](https://github.com/metosin/malli) | Data-driven schema validation, shared between client and server |
| Logging | [mulog](https://github.com/BrunoBonacci/mulog) | Structured event logging with pluggable publishers |
| Container build | [jibbit](https://github.com/atomisthq/jibbit) | Builds OCI images directly from deps.edn, no Dockerfile |

### What was replaced and why

| Layer | Before | Now | Reason for change |
|-------|--------|-----|-------------------|
| Database | Datalevin | Datahike | Datahike supports history tracking (`keep-history? true`) out of the box, essential for the post versioning feature. Also supports S3-backed storage for production (Datalevin has no S3 backend). |
| Frontend framework | Re-frame + Reagent | Replicant + dispatch-of | Re-frame's subscription/event system is powerful but heavyweight for this app. Replicant's `defalias` with plain hiccup is simpler, and a custom `dispatch-of` effect pattern gives the same unidirectional flow with less ceremony. |
| Frontend build | Figwheel-main | shadow-cljs | Better npm interop (`marked` and `@toast-ui/editor` from npm). shadow-cljs also handles hashed release builds natively. |
| HTTP server | Aleph | http-kit | Aleph's Netty-based async was unnecessary. http-kit is simpler and sufficient. |
| Repo structure | Standalone repo | Monorepo component | The app now lives in [lasagna-pattern/examples/flybot-site](https://github.com/flybot-sg/lasagna-pattern/tree/main/examples/flybot-site), using local deps to the `pattern`, `collection`, and `remote` components. |

## Deep dives

Each aspect of flybot.sg has its own article:

- **[Building a Pure Data API with Lasagna Pull](https://www.loicb.dev/blog/building-a-pure-data-api-with-lasagna-pull)**: how the backend uses collections, patterns, and role-based authorization to build a single-endpoint pull API
- **[Managing Web App Modes with Fun-Map in Clojure](https://www.loicb.dev/blog/managing-web-app-modes-with-fun-map-in-clojure)**: how fun-map's `life-cycle-map` wires the system together, with `assoc`-based overrides for dev, dev-with-oauth, and prod modes
- **[Building a ClojureScript SPA with Replicant and dispatch-of](https://www.loicb.dev/blog/building-a-clojurescript-spa-with-replicant-and-dispatch-of)**: the custom frontend architecture using effects-as-maps, a watcher pattern, and pure state functions
- **[Clojure Monorepo with Babashka](https://www.loicb.dev/blog/clojure-monorepo-with-babashka)**: how the monorepo is managed with auto-discovered components and two-layer task delegation
- **[Deploying a Clojure App to AWS with App Runner](https://www.loicb.dev/blog/deploying-a-clojure-app-to-aws-with-app-runner)**: the migration from EC2+ALB+NLB to App Runner with S3-backed storage
- **[Pull Playground - Interactive Pattern Learning](https://www.loicb.dev/blog/pull-playground-interactive-pattern-learning)**: the companion app at [pattern.flybot.sg](https://pattern.flybot.sg) for learning pull patterns interactively

The full source is in [lasagna-pattern/examples/flybot-site](https://github.com/flybot-sg/lasagna-pattern/tree/main/examples/flybot-site).
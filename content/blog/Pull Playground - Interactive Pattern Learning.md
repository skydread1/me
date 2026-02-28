---
tags:
  - clojure
  - clojurescript
  - architecture
  - sci
  - web
  - aws
date: 2026-02-17
repos:
  - [lasagna-pattern, "https://github.com/flybot-sg/lasagna-pattern"]
rss-feeds:
  - all
  - clojure
---
## TLDR

[Pull Playground](https://pattern.flybot.sg) is an interactive SPA for learning the [lasagna-pull](https://github.com/flybot-sg/lasagna-pattern) pattern DSL. Two modes: sandbox (runs entirely in the browser via SCI) and remote (sends patterns to a live server). Same UI, same pull engine, different transport.

## Context

The [lasagna-pull](https://github.com/flybot-sg/lasagna-pattern) pattern DSL is central to how we build APIs at Flybot (see [Building a Pure Data API with Lasagna Pull](https://www.loicb.dev/blog/building-a-pure-data-api-with-lasagna-pull)). But learning the syntax from documentation alone is slow. You need to type patterns, see results, and build intuition through experimentation.

I built the playground as a companion to [flybot.sg](https://www.flybot.sg/). The goal was a zero-setup environment where someone could open a URL and start writing patterns immediately, without cloning a repo, starting a REPL, or connecting to a database.

## Two modes, one UI

The playground supports two modes, toggled via URL path (`/sandbox`, `/remote`):

| Mode | How it works | Backend needed? |
|------|-------------|-----------------|
| Sandbox | SCI evaluates patterns in-browser against sample data | No |
| Remote | HTTP POST to a live server API (e.g. [flybot.sg](https://www.flybot.sg/api)) | Yes |

The UI is mode-agnostic. Views dispatch `{:pull :pattern}` and the effect system routes to the right executor (see [Building a ClojureScript SPA with Replicant and dispatch-of](https://www.loicb.dev/blog/building-a-clojurescript-spa-with-replicant-and-dispatch-of)). Switching modes changes the transport, not the interface.

**Sandbox** is the default and the one most people use. It ships with progressive examples that teach the DSL step by step: binding scalars, querying collections, using `:when` constraints, composing across collections, and performing mutations (create, update, delete). Each example loads a pre-filled pattern into the editor. For mutations, the data panel refreshes automatically so you can see the effect.

**Remote** connects to a live server and sends the same Transit-encoded patterns that [flybot.sg](https://www.flybot.sg) uses for its own frontend. This is useful for testing patterns against real data or debugging API behavior. Remote mode also adds schema-aware autocomplete tooltips from the server's Malli schema.

## Why SCI

Pull patterns support `:when` constraints with predicate functions:

```clojure
{:posts {{:id 1} {:title (?t :when string?)}}}
```

On a server, `string?` resolves from `clojure.core`. In the browser, there is no Clojure runtime. [SCI](https://github.com/babashka/sci) (Small Clojure Interpreter) fills this gap: it provides a sandboxed Clojure evaluator in ClojureScript.

The sandbox initializes SCI with a curated whitelist of safe functions (`pos?`, `string?`, `count`, `=`, etc.) covering what people actually use in `:when` constraints. No `eval`, no IO, no side effects.

The key insight is that the same `remote/execute` function runs in both modes. On the server, it uses Clojure's built-in `resolve`. In the sandbox, it uses SCI's resolve and eval. The pull engine does not know or care which one it is talking to.

## Same engine, in-memory data

The sandbox needs something that behaves like a database. The `collection` library provides `atom-source`, an in-memory implementation backed by atoms that supports the same CRUD operations as a real `DataSource`. The sandbox store is a map of atom-source-backed collections, initialized with sample data (users, posts, config).

Two design choices worth noting:

**Schema is pull-able data.** Instead of a separate endpoint, the schema lives in the store alongside domain collections. Querying `{:schema ?s}` returns it through the same pull mechanism as `{:users ?all}`. The playground uses pull for everything, including introspecting its own API.

**Reset is a pull mutation.** Resetting data to defaults is expressed as `{:seed {nil true}}`, a standard create mutation. The seed entry resets all atom-sources to their initial state. No special reset endpoint, no reload.

## Deployment

The sandbox runs entirely in the browser, so the playground is a pure SPA with no server dependency. This makes hosting straightforward: an S3 bucket behind CloudFront, deployed via GitHub Actions on git tag. Total cost is under $1/month.

The deploy pipeline reuses the same CI/CD setup as [flybot.sg](https://www.flybot.sg/). A `bb tag examples/pull-playground 0.1.0` triggers a shadow-cljs release build, S3 sync, and CloudFront cache invalidation.

The full source is in [lasagna-pattern/examples/pull-playground](https://github.com/flybot-sg/lasagna-pattern/tree/main/examples/pull-playground).
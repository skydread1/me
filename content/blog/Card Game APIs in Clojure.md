---
tags:
  - clojure
  - architecture
  - game-dev
date: 2020-01-06
repos:
  - [clr.test.check, "https://github.com/skydread1/clr.test.check/tree/magic"]
rss-feeds:
  - all
  - clojure
---
## TLDR

A protocol-driven architecture for card game backends in Clojure, where every game implements the same `Game` protocol and a `meta-game` engine composes them recursively via [EDN](https://github.com/edn-format/edn) configuration: rounds, scoring strategies, tournaments. All games run on both JVM and CLR (Unity), and external engineers have used the stack to implement their own games.

## Context

Flybot provides backend services for [Golden Island](https://www.80166.com/), a gaming platform with 18 card games for the Chinese market. Among them are climbing games like Big Two (锄大地) and Pao De Kuai (跑得快). When I joined the company, the existing game backends were written in Java. The codebase was difficult to extend: adding a new game or changing scoring rules required modifying deeply nested mutable state machines.

I was a few months into my first job and still learning Clojure. Mr Chen, an experienced Clojure engineer on the China team, collaborated with me on the protocol design and early implementation. He brought deep domain knowledge of the games and helped shape the abstractions. The architecture we arrived at has two layers: a `Game` protocol that every game implements, and a `meta-game` engine that composes games recursively.

## The Game protocol

The `game-protocols` library defines a Clojure protocol that every game must implement. The protocol covers game flow (advancing state, detecting end conditions), state inspection (available moves, cards), results (scoring, winner), and AI support (possible moves, auto-play). The surface is intentionally broad because different consumers need different slices: the game server needs flow and results, the AI engine needs possible moves and state transitions, the frontend needs state inspection.

A multimethod dispatches on a `:game-type` keyword to construct the initial state from a properties map. This means any code that depends on the protocol (the AI engine, the meta-game compositor, the game server) works with any game without modification. The MCTS-based AI described in [Monte Carlo Tree Search for Card Games in Clojure](https://www.loicb.dev/blog/monte-carlo-tree-search-for-card-games-in-clojure) depends solely on this protocol.

## Meta-game: recursive composition

The `meta-game` library wraps any game that implements the protocol and adds round management, scoring, and end conditions. The key insight: `MetaGame` itself implements the `Game` protocol, so it can be wrapped by another `MetaGame` to form tournaments.

```
Game protocol
    ├── Big Two      (implements Game)
    ├── PDK          (implements Game)
    ├── MetaGame     (wraps a Game, implements Game)
    │   └── sub-game: Big Two, PDK, or another MetaGame
    └── Tournament   (wraps Stages of MetaGames, implements Game)
```

A `MetaGame` is configured entirely via an EDN properties map. The config specifies which sub-game to play, when to stop (after N rounds, or when a score target is reached), how to set up the next round (winner goes first, random order, cyclic), and how to calculate scores (per-round rules like remaining-card penalties, plus a final aggregation rule).

Three pluggable strategy types control this behavior, each dispatched via multimethod on a `:type` keyword. Adding a new end condition or scoring rule is one function definition, no changes to meta-game itself.

## Tournaments as data

A tournament is a sequence of stages. Each stage runs matches (meta-games) in parallel, and winners advance to the next stage. The entire structure, from stage brackets to per-stage scoring rules and sub-game types, is described as a single EDN map. No tournament-specific code: the game server calls the same protocol methods it would for a single game of Big Two.

This makes it possible to configure setups like: 12 players in semi-final groups of 4 playing Big Two for 3 rounds, with the 3 winners advancing to a PDK final that ends when someone reaches a score target. Different scoring rules per stage, different sub-games, different player counts, all expressed as data.

## Testing with generated games

Game state has complex interdependencies: a player's available moves depend on the cards in hand, the cards on the table, and the game phase. Standard example-based tests cannot cover the combinatorial space.

I used [test.check](https://github.com/clojure/test.check) to build generators that produce valid game sequences: random but legal move chains that exercise the full state machine. The integration test suite runs hundreds of generated games per CI build, verifying that the protocol contract holds across random inputs. I also [ported test.check to the CLR](https://github.com/skydread1/clr.test.check/tree/magic) so the same property-based tests run on both JVM and .NET.

## CLR portability

All game libraries are written as `.cljc` with reader conditionals for the few JVM/CLR divergences. They compile to .NET assemblies via the [MAGIC](https://github.com/nasser/magic) compiler and run inside Unity for the game frontends. The compilation and packaging pipeline is covered in [MAGIC Compiler and Nostrand Integration](https://www.loicb.dev/blog/magic-compiler-and-nostrand-integration) and [Porting Clojure Libraries to the CLR with MAGIC](https://www.loicb.dev/blog/porting-clojure-libraries-to-the-clr-with-magic).

## Adoption

This was my first major project at Flybot. We built Big Two and PDK as the initial implementations of the protocol. Golden Island's China team then used these as reference to port most of their other Java game backends to Clojure, following the same pattern: implement `game-protocols`, verify compatibility with `meta-game`, port to the CLR, and import into Unity. The architecture has been running in production for years. The fact that their engineers could adopt the stack independently validated the protocol design.
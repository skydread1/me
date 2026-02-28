---
tags:
  - clojure
  - algorithms
  - architecture
  - game-dev
date: 2021-07-19
rss-feeds:
  - all
---
## TLDR

A draft implementation of a game-agnostic AI for Flybot's card games using Monte Carlo Tree Search. The implementation depends solely on a `Game` protocol from a shared library, so any game that implements the protocol gets MCTS for free. So far it has only been tested with two games (Big Two, PDK) and is not yet used in production. A colleague will be iterating on it next. Pure MCTS turned out to be too slow for practical use on the frontend (Unity/.NET), so we use domain-knowledge heuristics there. On the JVM side, the hybrid approach (heuristics for early game, MCTS for endgame) shows promise as a backend AI but needs more validation.

## Context

Flybot needed an AI player for its card games that could replace AFK players and provide different difficulty levels for offline play. The games (Big Two, PDK) are played over multiple rounds as a meta-game with scoring across rounds.

Card games like Big Two have **imperfect information**: you do not know your opponents' cards. This makes standard MCTS less effective, because simulations rely on random **rollouts** from **incomplete game states**. Two ways to handle this: pre-filter moves using **domain knowledge** (discard obviously bad plays), or use **determinization** (simulate with guessed opponent hands). I ended up needing both.

## The Game protocol

Before writing any AI logic, I made sure it would work across multiple games. The MCTS implementation depends only on a `Game` protocol from our shared `game-protocols` library. Both Big Two and PDK implement this protocol, so the same AI code works for both without modification. The protocol exposes the operations MCTS needs: possible plays, state transitions, scoring, and turn order.

## Why pure MCTS did not work

MCTS builds a search tree by repeatedly **selecting**, **expanding**, **simulating**, and **back-propagating** results. The more iterations, the better the tree represents the most rewarding paths. In theory.

In practice, card games have a **branching factor** problem. At the start of a Big Two round with 13 cards in hand, the number of possible plays is large. With `{:nb-rollouts 10 :budget 30}` (10 simulations per state, 30 tree-growth iterations), the first move took over 40 seconds in Clojure. And those parameters are not even high enough to guarantee good play.

After compilation to .NET via [MAGIC](https://github.com/nasser/magic) for Unity, performance dropped further. MCTS was unusable on the client, so the frontend games use domain-knowledge heuristics exclusively.

## Domain knowledge

Most possible plays in a card game are obviously bad. A human player would never break a strong five-card combination just to cover a single card. The AI needed the same kind of reasoning.

I implemented a **game plan** system: the AI arranges its hand into **non-overlapping combinations** (pairs, triples, five-card hands) and reasons about them as units. On top of this, the AI evaluates:

- **Control analysis**: which combination types can take or keep control of the table
- **Win prediction**: whether the AI can finish after gaining control
- **Safe play detection**: whether playing a card would let an opponent win

This domain knowledge serves two purposes. When combined with MCTS, it **prunes the search tree** by filtering out bad branches before simulation begins. And it works as a standalone strategy when MCTS is too expensive.

## The hybrid approach

The final design uses a `max-cards` threshold. When the total cards remaining across all players exceeds the threshold (default: 16), the AI uses domain knowledge only. Below the threshold, MCTS kicks in with a manageable search space.

In practice, most of the game is played with domain knowledge. MCTS contributes to endgame positions where the branching factor is small enough for simulations to converge in reasonable time.

On the frontend (Unity/.NET), the games run with `{:max-cards 0}`, disabling MCTS entirely and relying on domain-knowledge heuristics. The hybrid approach with MCTS enabled is used on the JVM side, where performance is sufficient for backend AI decisions.

## Current status

This is a working draft. The MCTS implementation has only been tested with two games (Big Two and PDK) and is not yet deployed to production. A colleague will be picking this up to iterate on the hybrid approach and validate it against a wider set of games and edge cases.

## What I learned

- **Protocol-driven design pays off.** The entire MCTS implementation depends on a single `Game` protocol. Any game that implements it gets AI for free: no game-specific code in the AI module. This is the most reusable part of the project.
- **Start with the simplest approach that might work.** Domain knowledge alone turned out to be surprisingly effective. The AI does not need to simulate thousands of games to avoid obvious mistakes.
- **MCTS needs a manageable branching factor.** Card games at the start of a round have too many possible plays for MCTS to explore meaningfully within a time budget. The hybrid approach bounds the problem.
- **Compilation target matters.** The same Clojure code that was slow-but-usable on the JVM became unusable after MAGIC compilation. Performance characteristics of the target runtime should inform algorithm choice early, not after implementation.
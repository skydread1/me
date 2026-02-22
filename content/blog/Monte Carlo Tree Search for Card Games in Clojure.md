---
tags:
  - clojure
  - algorithms
  - architecture
  - game-dev
date: 2021-07-19
---
## TLDR

I built an AI for Flybot's card games (Big Two, PDK) using Monte Carlo Tree Search. Pure MCTS turned out to be too slow for practical use, especially after compilation to .NET via MAGIC for Unity. The solution was a hybrid: domain knowledge for early game decisions, MCTS only for endgame positions with few cards remaining. The AI depends solely on a Game protocol, making it game-agnostic.

## Context

Flybot needed an AI player for its card games that could replace AFK players and provide different difficulty levels for offline play. The games (Big Two, PDK) are played over multiple rounds as a meta-game with scoring across rounds.

Card games like Big Two have **imperfect information**: you do not know your opponents' cards. This makes standard MCTS less effective, because simulations rely on random **rollouts** from **incomplete game states**. Two ways to handle this: pre-filter moves using **domain knowledge** (discard obviously bad plays), or use **determinization** (simulate with guessed opponent hands). I ended up needing both.

## The Game protocol

Before writing any AI logic, I made sure it would work across multiple games. The MCTS implementation depends only on a `Game` protocol from our shared `game-protocols` library. Both Big Two and PDK implement this protocol, so the same AI code works for both without modification. The protocol exposes the operations MCTS needs: possible plays, state transitions, scoring, and turn order.

## Why pure MCTS did not work

MCTS builds a search tree by repeatedly **selecting**, **expanding**, **simulating**, and **back-propagating** results. The more iterations, the better the tree represents the most rewarding paths. In theory.

In practice, card games have a **branching factor** problem. At the start of a Big Two round with 13 cards in hand, the number of possible plays is large. With `{:nb-rollouts 10 :budget 30}` (10 simulations per state, 30 tree-growth iterations), the first move took over 40 seconds in Clojure. And those parameters are not even high enough to guarantee good play.

After compilation to .NET via [MAGIC](https://github.com/nasser/magic) for Unity, performance dropped by roughly another order of magnitude. MCTS was unusable on the client.

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

Setting `{:max-cards 0}` disables MCTS entirely. This is what runs fastest and is what we deployed to Unity for Big Two.

## What I learned

- **Start with the simplest approach that might work.** Domain knowledge alone turned out to be surprisingly effective. The AI does not need to simulate thousands of games to avoid obvious mistakes.
- **MCTS needs a manageable branching factor.** Card games at the start of a round have too many possible plays for MCTS to explore meaningfully within a time budget. The hybrid approach bounds the problem.
- **Protocol-driven design pays off.** Making the AI depend on a Game protocol meant it worked for PDK as well, without changes to the AI code.
- **Compilation target matters.** The same Clojure code that was slow-but-usable on the JVM became unusable after MAGIC compilation. Performance characteristics of the target runtime should inform algorithm choice early, not after implementation.
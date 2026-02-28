---
tags:
  - clojure
  - dotnet
  - unity
  - devops
date: 2021-02-01
repos:
  - [magic, "https://github.com/nasser/magic"]
  - [nostrand, "https://github.com/nasser/nostrand"]
  - [Magic.Unity, "https://github.com/nasser/Magic.Unity"]
rss-feeds:
  - all
---
## TLDR

Flybot uses Unity for card game frontends and Clojure for backend logic. To share code between the two without rewriting in C#, we needed a compiler that produces .NET assemblies compatible with Unity's IL2CPP runtime. I collaborated with [Ramsey Nasser](https://github.com/nasser), the author of the [MAGIC](https://github.com/nasser/magic) compiler, to stabilize and optimize the toolchain. My main contributions were on the tooling side: NuGet packaging, private repo support, a CI pipeline, and the suggestion to strip compilation out of Magic.Unity and route everything through Nostrand. The result is a three-command workflow that frontend developers have been using in production for years.

## Context

Flybot builds card games. The backend (game rules, state machines, scoring) is written in Clojure. The frontend is Unity. For a long time, any logic shared between the two had to be maintained twice: once in Clojure, once in C#. We wanted to eliminate that duplication, and we were also exploring an Entity Component System architecture on the Unity side where calling Clojure libraries directly would fit naturally into a data-oriented stack.

The shared libraries are written as `.cljc` files with `:clr` reader conditionals for the few places where JVM and CLR diverge (mostly IO types in `print-method` and occasional polyfills). The bulk of the code is plain Clojure that compiles to both targets unchanged.

The standard path for running Clojure on .NET is [clojure-clr](https://github.com/clojure/clojure-clr), but it relies on the [Dynamic Language Runtime](https://en.wikipedia.org/wiki/Dynamic_Language_Runtime) (DLR) to optimize dynamic call sites. The DLR generates code at runtime, which is prohibited by [IL2CPP](https://docs.unity3d.com/Manual/IL2CPP.html), Unity's ahead-of-time compilation pipeline for mobile builds. Any assemblies that depend on runtime code generation simply cannot run on iOS.

[MAGIC](https://github.com/nasser/magic) (Magic Is A Clojure Generator), created by [Ramsey Nasser](https://github.com/nasser), takes a different approach. It is a bootstrapped compiler written in Clojure that produces .NET assemblies without any DLR dependency. The resulting DLLs can run inside Unity on both desktop and mobile via IL2CPP.

The problem was that MAGIC was not yet stable enough to compile our card game libraries. Compilation would fail on various Clojure constructs we relied on. That is what led to a close collaboration with Ramsey.

## The four projects

Four open-source repos make up the toolchain:

- [nasser/magic](https://github.com/nasser/magic): the Clojure-to-.NET compiler itself, bootstrapped (compiled by its own previous version)
- [nasser/nostrand](https://github.com/nasser/nostrand): dependency manager and task runner for MAGIC. Projects declare dependencies in a `project.edn` (MAGIC predates `deps.edn`, so Nostrand has its own format)
- [nasser/Magic.Unity](https://github.com/nasser/Magic.Unity): runtime for loading MAGIC-compiled Clojure inside Unity
- [magic-clojure/magic](https://github.com/magic-clojure/magic): integration repo that builds all of the above with a single `dotnet build`

## Working with Ramsey

Ramsey built MAGIC from scratch, including a custom IL emitter ([MAGE](https://github.com/nasser/mage)). I do not have expertise in compiler internals, and I was upfront about that from the start.

What made the collaboration work was that Ramsey took the time to explain what he was doing, even knowing I would not fully grasp the low-level details. He regularly shared annotated gists showing disassembled C# output before and after his optimizations, walking through what changed and why. I could follow the high-level reasoning (boxing, type erasure, cache hits) even when the IL bytecode was beyond me.

My role was on the other side: filing detailed bug reports with reproduction steps from our actual codebase, improving the tooling around the compiler so our team could actually use it, and making sure we stayed focused on what we needed to get to production.

## What I built

### NuGet packaging and private repo support

Nostrand could fetch public dependencies, but we needed to compile private GitLab repositories with dependencies on both private and public repos. I added GitLab support and authentication using GitHub/GitLab tokens.

For packaging, NuGet turned out to be the cleanest way to distribute compiled assemblies. I added NuGet pack and push support to Nostrand: a `.csproj` referencing a `.nuspec` at the repo root, with a `nuget.config` for private registry credentials (PATs for GitHub, deploy tokens for GitLab).

### The build workflow

Each Clojure repo gets a `dotnet.clj` namespace with convenience functions callable via `nos`:

```bash
nos dotnet/build        # compile Clojure to .NET assemblies
nos dotnet/run-tests    # run all tests on the CLR
nos dotnet/nuget-push   # pack and push to GitHub/GitLab registry
```

The NuGet workflow was used initially to package compiled DLLs for consumption in Unity via `nuget restore`. Since then, the team has moved to pulling Clojure libraries as **git submodules** directly into the Unity project and compiling everything from source with a single `nos dotnet/build`. This simplified the pipeline by removing the NuGet packaging step entirely. The core two-command workflow remains: `nos dotnet/build` to compile and `nos dotnet/run-tests` to verify on the CLR.

### GitHub Action for bootstrapping

MAGIC is a bootstrapped compiler: it compiles itself. You need the previous version's DLLs to produce the next version's DLLs. I added a GitHub Action that performs this bootstrapping on every push, making the latest compiler DLLs available as build artifacts. Ramsey later incorporated this into the [magic-clojure/magic](https://github.com/magic-clojure/magic) integration repo with a more complete pipeline.

### Stripping compilation from Magic.Unity

This was probably my most impactful suggestion. Magic.Unity originally included both a compilation UI and a runtime. The compilation step used its own copy of MAGIC's DLLs, which frequently fell out of sync with the DLLs used by Nostrand. The version mismatch caused subtle runtime bugs, most notably inconsistent hashing between compile-time and runtime environments.

I proposed removing the compilation step entirely and keeping Magic.Unity as a pure runtime. All compilation would go through Nostrand, which always has the latest MAGIC DLLs. This eliminated the dual-pipeline maintenance burden and the version mismatch bugs. Ramsey agreed and implemented the refactor.

## What Ramsey improved

Our collaboration had two phases. The first focused on performance, the second on stability and production readiness.

### Performance

Ramsey tackled two categories of optimization in the compiler:

**Static call sites.** Clojure functions normally go through `IFn.invoke`, which takes `Object` arguments and returns `Object`, erasing type information and forcing boxing of value types. Ramsey added typed invocation paths (`invokeTyped`, `invokeStatic`) that take advantage of CLR generics to preserve types through the call chain. With type hints, compiled Clojure functions now produce output close to what the C# compiler generates.

**Dynamic call sites.** When types are not known at compile time, MAGIC previously fell back to runtime reflection. Ramsey replaced this with [Polymorphic Inline Caches](https://en.wikipedia.org/wiki/Inline_caching#Polymorphic_inline_caching) (PIC), a technique used by every production-grade dynamic language. The PIC caches resolved methods based on parameter types seen at runtime, avoiding reflection on subsequent calls. His implementation avoids runtime code generation (unlike the DLR), so it works under IL2CPP.

### Stability and production readiness

The second phase focused on the bugs I had been reporting that blocked us from running our games:

- **Inconsistent hashing**: compiling with Nostrand and running in Unity produced different hash values for `case` branches, because .NET's `GetHashCode` is not guaranteed stable across runtimes. Fixed by switching to Murmur3 for keywords and symbols (the common case). A subtler inconsistency remains for raw strings in `Util.hasheq`, which still wraps .NET's unstable `String.GetHashCode()`, but this is dormant in practice since Clojure idiom uses keywords almost everywhere.
- **Unstable sort**: `sort-by` violated `clojure.core/sort`'s stability guarantee due to the underlying .NET sort being unstable.
- **Record/datafy interaction**: a subtle clash between records, `walk`, and the typed invocation optimization caused runtime cast errors.
- **Bootstrap inconsistencies**: some namespaces compiled twice or not at all.
- **Magic.Unity refactor**: stripped the compilation UI, kept only the runtime (my suggestion, implemented here).
- **Integration repo**: [magic-clojure/magic](https://github.com/magic-clojure/magic) now builds the entire toolchain with a single `dotnet build`.

## The result

After the second phase, we successfully compiled and ran our card games in Unity, including the full dependency chain: public GitHub libraries and private GitLab repositories with their own transitive dependencies. Frontend developers on the team have been using MAGIC-compiled Clojure assemblies in production for years now.

I did not write compiler code. What I did was make it possible for a team to actually use the compiler: filing the right bugs, building the packaging pipeline, identifying the architectural problem with Magic.Unity's dual compilation paths, and managing a productive collaboration with someone whose expertise was far deeper than mine in that domain.
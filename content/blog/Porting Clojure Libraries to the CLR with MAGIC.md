---
tags:
  - clojure
  - dotnet
  - unity
  - testing
date: 2022-04-08
repos:
  - [clr.test.check, "https://github.com/skydread1/clr.test.check/tree/magic"]
rss-feeds:
  - all
  - clojure
---
## TLDR

A step-by-step approach to porting Clojure libraries to the CLR using the MAGIC compiler, covering reader conditionals, type hints, dependency management, and building/testing with Nostrand.

## Context

At Flybot, our card games run on Unity. The game logic (hand evaluation, state machines, protocol validation) lives in Clojure on the backend. Duplicating that logic in C# for the Unity frontend was not an option: two implementations of the same rules would inevitably drift, and every rule change would require coordinated updates across both codebases.

The natural solution was to compile our Clojure libraries to .NET assemblies (DLLs) and load them directly in Unity. An existing compiler, [clojure-clr](https://github.com/clojure/clojure-clr), already targets the CLR. But clojure-clr relies on the Dynamic Language Runtime (DLR), which emits self-modifying code at runtime. That works fine on desktop, but Unity's IL2CPP backend (required for iOS and Android builds) performs ahead-of-time compilation and cannot execute dynamically generated IL. The DLR-based assemblies simply do not work on mobile.

[MAGIC](https://github.com/nasser/magic) solves this. It is a bootstrapped Clojure compiler (written in Clojure itself) that emits static .NET assemblies with no DLR dependency. The output runs on both Mono (Unity desktop) and IL2CPP (Unity mobile). This article covers the practical steps to port a Clojure library so it compiles cleanly with MAGIC.

For background on the MAGIC compiler itself, Nostrand tooling, and the NuGet packaging pipeline, see the companion article [MAGIC Compiler and Nostrand Integration](https://www.loicb.dev/blog/magic-compiler-and-nostrand-integration).

## Step 1: Reader conditionals for CLR interop

We maintain a single codebase that runs on both JVM and CLR using Clojure's [reader conditionals](https://clojure.org/guides/reader_conditionals). The main areas that need platform-specific code are `require`/`import` forms, interop calls, and function parameters.

Rename source files from `.clj` to `.cljc` to enable reader conditionals.

```clojure
#?(:clj  (Clojure expression)
   :cljr (Clojure CLR expression))
```

There is no need for a `:default` branch since the code only runs on two platforms: `:clj` and `:cljr`.

### Interop examples

Basic method dispatch differs between JVM and CLR:

```clojure
(defn round-perc
  "Rounds the given `number`."
  [number]
  #?(:clj  (-> number double Math/round)
     :cljr (-> number double Math/Round long)))
```

### deftype equality overrides

In Java, equality uses `hashCode` and `equals`. On the CLR, you need `hasheq` and `equiv`:

```clojure
(deftype MyRecord [f-conj m rm]
  #?@(:clj
      [Object
       (hashCode [_] (.hashCode m))
       (equals [_ other]
               (and (instance? MyRecord other) (= m (.m other))))]
      :cljr
      [clojure.lang.IHashEq
       (hasheq [_] (hash m))
       clojure.lang.IPersistentCollection
       (equiv [_ other]
              (and (instance? MyRecord other) (= m (.m other))))]))
```

### defrecord empty override for IL2CPP (historical)

Earlier versions of the toolchain required manually overriding the `empty` method on records for IL2CPP compatibility:

```clojure
(defrecord PokerCard [^clojure.lang.Keyword suit ^clojure.lang.Keyword num]
  #?@(:cljr
      [clojure.lang.IPersistentCollection
       (empty [_] nil)]))
```

Note the splicing reader conditional `#?@`: it requires a vector. This is no longer needed manually in newer projects: Magic.Unity's IL2CPP post-processing pipeline handles it automatically.

### Linter configuration

If you use [clj-kondo](https://github.com/clj-kondo/clj-kondo), set `:clj` as the default reader so the linter does not flag `:cljr` branches as errors. Add this to `~/.config/clj-kondo/config.edn`:

```clojure
{:cljc {:features #{:clj}}}
```

The `:cljr` code will appear as comments in your editor, while `:clj` branches get full linting.

## Step 2: Add type hints (optional but recommended)

MAGIC supports the same [type hint shorthands](https://github.com/nasser/magic/blob/master/src/magic/analyzer/types.clj#L37) as Clojure. Adding hints prevents slow argument boxing and reflection at runtime, which matters for game logic running at 60fps.

Keep type hints inside `:cljr` reader conditionals. Clojure on the JVM should remain dynamically typed so your existing test suite, which often passes `nil`, plain maps, or keywords where records are expected, continues to work.

### Value types

```clojure
(defn straights-n
  "Returns all possible straights with given length of cards."
  #?(:clj  [n cards wheel?]
     :cljr [^int n cards ^Boolean wheel?])
  (...))
```

### Record types

Import the record class first to avoid fully qualified names in hints:

```clojure
#?(:cljr (:import [myproj.combination Combination]))
```

Then hint parameters and record fields:

```clojure
(defn pass?
  "Returns true if the combi is a pass."
  #?(:clj [combi]
     :cljr [^Combination combi])
  (combi/empty-combi? combi))

(defrecord Player #?(:clj  [combi penalty?]
                     :cljr [^Combination combi
                            ^boolean penalty?]))
```

### Limitations

MAGIC cannot type-hint collections. On the JVM, you can hint a vector of records with `^"[Lmyproj.PokerCard;"`, but MAGIC has no equivalent. Maps are similarly impractical to hint since the concrete type (`PersistentArrayMap`, `PersistentHashMap`, `PersistentTreeMap`) depends on size.

### Impact on tests

CLR type hints will break tests that pass simplified values (e.g., `nil` instead of `false`, a map instead of a record). Rather than changing the test suite, wrap the affected tests in `:clj` reader conditionals and write CLR-specific equivalents in `:cljr` where needed.

## Step 3: Manage dependencies

MAGIC predates `tools.deps` and Leiningen, so it uses its own dependency format: `project.edn`.

```clojure
{:name         "My project"
 :source-paths ["src" "test"]
 :dependencies [[:github skydread1/clr.test.check "magic"
                 :sha "a23fe55e8b51f574a63d6b904e1f1299700153ed"
                 :paths ["src"]]
                [:gitlab my-org/my-private-lib "master"
                 :paths ["src"]
                 :sha "abcdef1234567890abcdef1234567890abcdef12"
                 :token "TOKEN"
                 :domain "gitlab.example.com"
                 :project-id "123"]]}
```

Dependencies are pulled from GitHub or GitLab by SHA, with optional token-based auth for private repos. Place `project.edn` at the root of your project alongside `deps.edn`.

See the Nostrand [README](https://github.com/nasser/nostrand/blob/master/README.md) for the full dependency specification.

## Step 4: Compile to the CLR

[Nostrand](https://github.com/nasser/nostrand) is MAGIC's build tool and dependency manager. Create a `dotnet.clj` namespace at your project root with a `build` function:

```clojure
(ns dotnet
  (:require [magic.flags :as mflags]))

(def prod-namespaces
  '[myproj.core myproj.utils])

(defn build
  "Compiles the project to DLLs.
  nos dotnet/build"
  []
  (binding [*compile-path*                  "build"
            *unchecked-math*                *warn-on-reflection*
            mflags/*strongly-typed-invokes* true
            mflags/*direct-linking*         true
            mflags/*elide-meta*             false]
    (println "Compile into DLL to:" *compile-path*)
    (doseq [ns prod-namespaces]
      (println (str "Compiling " ns))
      (compile ns))))
```

The two critical flags are `*strongly-typed-invokes*` and `*direct-linking*`, both must be `true` for IL2CPP-compatible output. Build with:

```bash
nos dotnet/build
```

DLLs are emitted to the `build/` directory.

## Step 5: Test on the CLR

Add a `run-tests` function to the same `dotnet.clj`:

```clojure
(defn run-tests
  "Run all tests on the CLR.
  nos dotnet/run-tests"
  []
  (binding [*unchecked-math*                *warn-on-reflection*
            mflags/*strongly-typed-invokes* true
            mflags/*direct-linking*         true
            mflags/*elide-meta*             false]
    (doseq [ns (concat prod-namespaces test-namespaces)]
      (require ns))
    (run-all-tests)))
```

Run with:

```bash
nos dotnet/run-tests
```

This loads all namespaces into the CLR and runs `clojure.test` against them. Expect some tests to fail if they rely on JVM-specific dynamic behavior, those should be gated behind `:clj` reader conditionals as described in Step 2.

## Worked example: clr.test.check

[skydread1/clr.test.check](https://github.com/skydread1/clr.test.check/tree/magic) is a fork of `clojure/test.check` ported to MAGIC using the approach described here. It uses reader conditionals throughout, compiles and tests on both JVM and CLR, and is packaged as a NuGet package for use in Unity projects. Its [dotnet.clj](https://github.com/skydread1/clr.test.check/blob/magic/dotnet.clj) is a good reference for the build/test/package workflow.

## Conclusion

- **The core problem**: clojure-clr's DLR-based assemblies cannot run on Unity's IL2CPP backend, which is required for mobile. MAGIC emits static IL that works on both desktop and mobile.
- **Single codebase**: reader conditionals (`.cljc`) let you maintain one source tree for JVM and CLR, avoiding logic duplication.
- **Type hints matter for performance**: hint function arguments and record fields in `:cljr` branches to avoid boxing and reflection. Keep `:clj` dynamic for test flexibility.
- **The build pipeline**: `nos dotnet/build` to compile and `nos dotnet/run-tests` to verify on the CLR. The team now uses git submodules to pull Clojure libs into Unity and compiles from source, replacing the earlier NuGet packaging step (see [MAGIC Compiler and Nostrand Integration](https://www.loicb.dev/blog/magic-compiler-and-nostrand-integration)).
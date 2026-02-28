---
tags:
  - clojure
  - testing
  - babashka
date: 2024-08-10
rss-feeds:
  - all
  - clojure
---
## TLDR

How I structure testing across Clojure projects: Rich Comment Tests for internal functions, `deftest` for system boundaries, Malli validation at entry points only, embedded services for CI, and containerized services for load tests. All wrapped behind `bb test` and `bb rct` so nobody needs to remember Kaocha flags.

## Start with pure functions

The single most important thing for testability is isolating side effects. A pure function (same input, same output) is trivial to test. An impure function that reads config from a file, connects to a database, or checks an environment variable needs mocking, fixtures, and defensive setup.

```clojure
;; Impure: reads from file, result depends on file content
(defn fib [variable]
  (when-let [n (-> (slurp "config/env.edn") edn/read-string (get variable) :length)]
    (->> (iterate (fn [[a b]] [b (+' a b)]) [0 1])
         (map first)
         (take n))))

;; Pure: same input always yields the same output
(defn fib [n]
  (->> (iterate (fn [[a b]] [b (+' a b)]) [0 1])
       (map first)
       (take n)))
```

The pure version is testable with no setup. The impure version needs a file on disk with the right format. Isolate IO at the edges, keep the core logic pure. This is the foundation everything else builds on.

## Rich Comment Tests for internal functions

Most Clojure developers use `comment` blocks for REPL-driven exploration. [Rich Comment Tests](https://github.com/matthewdowney/rich-comment-tests) (RCT) turns these into executable tests by adding `^:rct/test` metadata:

```clojure
(defn fib [n]
  (->> (iterate (fn [[a b]] [b (+' a b)]) [0 1])
       (map first)
       (take n)))

^:rct/test
(comment
  (fib 10) ;=> [0 1 1 2 3 5 8 13 21 34]
  (fib 0)  ;=> []
  )
```

The `comment` block serves as both documentation and a test suite. The values after `;=>` are assertions verified when RCT runs. No separate test file, no `deftest` boilerplate.

RCT supports two assertion styles:

- `;=>` exact match: the result must equal the expected value
- `;=>>` matcho match: the expected value is a pattern. Maps only need to contain the specified keys, regexes match against strings, and predicates are called on the result

```clojure
^:rct/test
(comment
  ;; Exact match
  (validate-cfg valid-cfg)   ;=> valid-cfg

  ;; Matcho: only check that :error key is present with a string value
  (validate-cfg invalid-cfg) ;=>> {:error string?}

  ;; Matcho: exception was thrown
  (validate-cfg nil)         ;throws=>> some?
  )
```

The matcho style is particularly useful for functions that return rich maps where you only care about certain keys, or for error cases where the exact message does not matter but the shape does.

I use RCT for internal functions: pure logic that is not part of the public API. It keeps the tests next to the implementation, which makes them easy to maintain and useful as examples for anyone reading the code. For some projects, RCT alone is sufficient and Kaocha is not needed at all. The [lasagna-pattern](https://github.com/flybot-sg/lasagna-pattern) `collection` and `pattern` components, for example, use only RCT with `clojure -X:dev:rct` as their test command.

## deftest for system boundaries

RCT does not suit every case. System tests, integration tests, or anything requiring fixtures (starting/stopping services, database setup) belongs in a dedicated test namespace with `deftest`:

```clojure
(deftest ^:system system-test
  (testing "The system returns the Fib sequence given a valid config."
    (is (= [0 1 1 2 3 5 8 13 21 34]
           (sut/system #:cfg{:app #:app{:name "app" :version "1.0.0"}
                             :fib #:fib{:length 10}})))))
```

My rule of thumb: use `deftest` when the test requires at least one of:

- **Fixtures**: starting and tearing down resources (database, Kafka, entire system)
- **Verbose setup**: configs, logging, service initialization
- **Side effects**: testing the full system, load tests, stress tests

Everything else gets RCT.

## Kaocha as test runner

[Kaocha](https://github.com/lambdaisland/kaocha) runs both `deftest` and RCT tests. The RCT tests are wrapped in a single `deftest` that scans the source tree:

```clojure
(deftest ^:rct rich-comment-tests
  (testing "all rich comment tests"
    (rctr/run-tests-in-file-tree! :dirs #{"src"})))
```

Kaocha's `tests.edn` config groups tests by metadata. This lets me run subsets: all tests, only unit tests, only system tests, only RCT:

```clojure
#kaocha/v1
{:tests [{:id :system :focus-meta [:system]}
         {:id :unit}]
 :plugins [:kaocha.plugin/profiling
           :kaocha.plugin/cloverage]}
```

For development, a separate `tests_watch.edn` skips slow system tests and enables watch mode with fail-fast, without the profiling and coverage plugins cluttering the terminal.

## Babashka tasks over raw commands

The raw Kaocha command to run RCT tests with the right aliases and focus metadata is not something anyone should have to remember:

```bash
# Nobody wants to type this
clj -M:dev:test --focus-meta :rct
```

Every project gets Babashka tasks that wrap these:

```bash
bb test           # run all tests
bb rct            # run only RCT tests
bb test --watch   # watch mode, skip slow tests, fail-fast
```

In a monorepo like [lasagna-pattern](https://github.com/flybot-sg/lasagna-pattern), `bb test` delegates to each component's own `bb test`, so the same command works at the root or in any subproject. The developer does not need to know which aliases or flags a particular component uses.

## Malli at entry points only

[Malli](https://github.com/metosin/malli) handles data validation, generation, and function instrumentation. The temptation is to add schemas everywhere. I do the opposite: schemas go on **entry point functions only**.

```clojure
(defn system
  {:malli/schema
   [:=> [:cat cfg/cfg-sch] [:sequential :int]]}
  [cfg]
  (let [length (-> cfg :cfg/fib :fib/length)]
    (fib/fib length)))
```

Clojure's dynamic nature makes testing easy because you can mock inputs, provide partial data, and iterate quickly. Adding schemas to every internal function reintroduces the rigidity of static typing without the compile-time guarantees. Schema the boundaries, keep the internals flexible.

An important distinction: **instrumentation** (`malli.dev/start!`) is a development tool that validates function arguments and return values at runtime. It should not run in production. **Data validation** (`m/validate`, `m/explain`) is production code that checks external input (configs, API requests). Both use the same Malli schemas but serve different purposes.

## Integration tests: embedded services

For apps that depend on external services (Kafka, Datomic, HTTP APIs), I use **embedded** versions in integration tests. Test fixtures start and stop an embedded database, embedded Kafka consumers/producers, etc. HTTP calls to external services get mocked with `with-redefs` to return valid but controlled data.

Some people argue you should never mock. For complex apps that consume from multiple Kafka topics, read/write from Datomic, and call several remote APIs, running all real services in a test environment is not practical. The goal of integration tests is verifying that components work together correctly, and embedded services with controlled inputs achieve that while remaining deterministic and CI-friendly.

## Load and stress tests: containerized services

Embedded services are limited in performance and parallelism. For load testing, I run real services in containers: Datomic transactor, Kafka (via Confluent's [cp-all-in-one](https://github.com/confluentinc/cp-all-in-one)), Prometheus, Grafana. A `docker-compose.yml` in the repo lets any developer run `docker-compose up -d` to start everything.

The Clojure app itself stays outside Docker. I run it from my REPL as usual and point it at the containerized services. This means load/stress tests use the exact same workflow as unit tests: write the test, run it from the REPL or via `bb test`. The only difference is that heavier infrastructure is running in the background.
---
tags:
  - clojure
  - clojurescript
  - architecture
  - replicant
  - web
date: 2026-02-17
repos:
  - [lasagna-pattern, "https://github.com/flybot-sg/lasagna-pattern"]
---
## TLDR

A custom frontend architecture for ClojureScript SPAs using Replicant, where effects are maps, dispatch is a closure, and the state layer is pure `.cljc` testable on the JVM. Used in both [flybot.sg](https://www.flybot.sg) and [pattern.flybot.sg](https://pattern.flybot.sg).

## Context

[Replicant](https://github.com/cjohansen/replicant) is a lightweight ClojureScript rendering library built around `defalias` components and plain hiccup. Unlike Re-frame, it has no built-in state management, event system, or subscription layer. You bring your own.

The conventional Replicant approach is to return **action descriptors** (data) from event handlers and dispatch them through a central multimethod:

```clojure
;; Traditional Replicant: actions as data
[:button {:on {:click [:save-post {:id 1}]}}]

;; Central handler dispatches by keyword
(defmethod handle-action :save-post [_ params] ...)
```

We took a different path: components **close over `dispatch!`** and call it directly with an **effect map**. The idea originated from [@Robert Luo](https://github.com/robertluo) and the implementation was first iterated on in an internal project by a [colleague](https://github.com/chickendreanso) and myself. We then applied it to both [flybot-site](https://github.com/flybot-sg/lasagna-pattern/tree/main/examples/flybot-site) and [pull-playground](https://github.com/flybot-sg/lasagna-pattern/tree/main/examples/pull-playground).

## Effects as maps

Instead of dispatching a single action keyword, views dispatch a map where each key is an independent effect type:

```clojure
;; Multiple effects in one dispatch
(dispatch! {:db    state/set-loading
            :pull  :pattern
            :nav   :remote})
```

| Effect | Value | What happens |
|--------|-------|--------------|
| `:db` | `(fn [db] db')` | Pure state update via `swap!` |
| `:pull` | `:keyword` or `{:pattern ... :then ...}` | Named pull operation or inline spec |
| `:nav` | `:keyword` | `pushState` URL navigation |
| `:history` | `:push` | pushState from current state (flybot-site) |
| `:confirm` | `{...}` | Confirmation modal (flybot-site) |
| `:toast` | `{...}` | Auto-dismissing notification (flybot-site) |
| `:navigate` | `"/url"` | Hard browser redirect (flybot-site) |

This composes naturally: a button click can update state, trigger an API call, and navigate, all in one map. No action registry, no multimethod. The effect map IS the action.

## dispatch-of: the effect router

`dispatch-of` creates a stable `dispatch!` closure that routes effects by key. Two design details matter:

1. **Fixed execution order**: effects run in a defined sequence (`:db` first, then `:pull`, then navigation), not in map iteration order. This ensures `:pull` always sees the updated state from `:db`.
	1. **Volatile self-reference**: async callbacks (pull responses, toast timers) need to call `dispatch!` back. Instead of recreating the closure, a `volatile!` holds a stable self-reference.

```clojure
(def ^:private effect-order [:db :pull :nav])

(defn dispatch-of [app-db root-key]
  (let [self (volatile! nil)
        dispatch!
        (fn [effects]
          (doseq [type effect-order
                  :let [effect-def (get effects type)]
                  :when (some? effect-def)]
            (case type
              :db   (swap! app-db update root-key effect-def)
              :pull (let [db   (get @app-db root-key)
                          spec (pull/resolve-pull effect-def db)]
                      (when-let [{:keys [pattern then]} spec]
                        (exec pattern
                              (fn [r] (@self (if (fn? then) (then r) then)))
                              (fn [e] (@self {:db #(db/set-error % e)})))))
              :nav  (.pushState js/history nil "" (str "/" (name effect-def))))))]
    (vreset! self dispatch!)
    dispatch!))
```

The `@self` dereference inside callbacks is the key trick: pull responses dispatch back through the same stable closure without needing to pass `dispatch!` as an argument. This enables chained async flows where one pull operation's `:then` triggers another dispatch.

## Pull specs: pattern + :then

Pull handling is inlined in `dispatch-of`, not a separate function. The named operations live in a `resolve-pull` function that returns pure data specs:

```clojure
;; resolve-pull returns {:pattern <pull-data> :then <fn or map>}
(defn resolve-pull [op db]
  (case op
    :init    {:pattern '{:guest {:posts ?posts} :member {:me ?user}}
              :then    (fn [r] {:db #(db/init-fetched % r)})}
    :pattern (let [p (read-pattern (:pattern-text db))]
               {:pattern p
                :then    (fn [r] {:db #(db/set-result % r)})})
    ...))
```

The `:then` value is either a function `(fn [response] effect-map)` or a static effect map. Either way, `dispatch-of` calls `@self` with it, feeding the result back into the effect loop. This is how chaining works: a `:then` can return `{:db ... :pull :another-op}` to trigger further operations.

In the playground, `resolve-pull` handles three shapes depending on the operation and mode:

| Shape | When | Example |
|-------|------|---------|
| `{:pattern ... :then ...}` | Standard pull | `:data`, `:schema`, sandbox `:init` |
| `{:fetch url :then ...}` | HTTP GET (remote schema) | Remote `:init` |
| `{:error msg}` | Validation failure | Empty pattern text |

The `:pull` branch in `dispatch-of` checks which shape it received and routes accordingly.

## make-executor: mode-agnostic transport

In [pull-playground](https://pattern.flybot.sg), the same UI works in two modes: sandbox (SCI evaluates patterns in-browser) and remote (HTTP POST to a backend). `make-executor` is the ONLY mode-specific function:

```clojure
(defn- make-executor [db]
  (case (:mode db)
    :sandbox (fn [pattern on-success on-error]
               (let [{:keys [result error]}
                     (sandbox/execute! (:sandbox/store db) sandbox/store-schema pattern)]
                 (js/queueMicrotask (if error #(on-error error) #(on-success result)))))
    :remote  (fn [pattern on-success on-error]
               (pull! (:server-url db) pattern on-success on-error))))
```

In sandbox mode, `sandbox/execute!` calls `remote/execute` in-process via SCI. The result is synchronous, so `js/queueMicrotask` defers the callback to avoid recursive dispatch (the callback will call `@self`, which would re-enter `dispatch!` mid-execution). In remote mode, the HTTP promise handles this naturally since `.then` is already async.

Everything else in the `:pull` handler is shared: resolving specs, calling `:then`, dispatching results back through `@self`.

## Module-level dispatch and watcher

`dispatch!` is created once as a module-level `def` and never recreated. An `add-watch` on the app-db atom triggers re-renders:

```clojure
(def dispatch! (dispatch-of app-db root-key))

(add-watch app-db :render
  (fn [_ _ _ state]
    (when-let [el (js/document.getElementById "app")]
      (r/render el (views/app-view {::views/db       (root-key state)
                                    ::views/dispatch! dispatch!})))))
```

Initialization dispatches the first pull to load data:

```clojure
(defn ^:export init! []
  (init-theme!)
  (dispatch! {:db db/set-loading :pull :init}))
```

This watcher pattern, borrowed from our internal analytics platform, avoids the common pitfall of recreating `dispatch!` on every render cycle. The closure is stable, so component identity is preserved across renders.

## Pure state layer

All state transitions live in a separate `.cljc` namespace as pure `db -> db` functions:

```clojure
;; db.cljc - testable on the JVM, no browser needed
(defn set-loading [db]
  (assoc db :loading? true :error nil))

(defn set-result [db result]
  (assoc db :loading? false :result result))

(defn set-mode [db mode]
  (-> db
      (assoc :mode mode)
      (assoc :result nil :error nil)))
```

Views dispatch these functions directly as the `:db` effect value. Because they are pure `.cljc`, they are testable on the JVM via Rich Comment Tests, no browser or CLJS compilation needed.

## View layer

Components use `defalias` with namespaced props. `dispatch!` is threaded through explicitly:

```clojure
(defalias pattern-panel [{::keys [db dispatch!]}]
  [:div.pattern-panel
   [:textarea {:value (:pattern-text db)
               :on    {:input #(dispatch! {:db (fn [db] (assoc db :pattern-text (.-value (.-target %))))})}}]
   [:button {:on {:click #(dispatch! {:db state/set-loading :pull :pattern})}}
    (if (:loading? db) "Running..." "Execute")]])
```

The trade-off vs. Replicant's global action system: components need `dispatch!` in their props. For small-to-medium apps, explicit threading is clearer than a global dispatch registry.

## Why not Re-frame

| Aspect | Re-frame | dispatch-of |
|--------|----------|-------------|
| State management | Subscription DAG + event handlers | Single atom + pure `db -> db` functions |
| Side effects | `reg-fx` + interceptor chain | Effect map keys (`:pull`, `:nav`) |
| Rendering | Reagent reactions | Replicant `defalias` + `add-watch` |
| Boilerplate | `reg-event-db`, `reg-sub`, `reg-fx` per feature | One `case` branch per effect type |
| Testing | Requires subscription/event test harness | Plain `.cljc` function tests |

Re-frame is powerful, but for apps of this size, the subscription registry and event interceptor chain add ceremony without proportional benefit. `dispatch-of` gives the same unidirectional flow with less machinery.

## Conclusion

- **Effects are maps, not action vectors**: multiple side effects compose in a single dispatch call
- **Fixed execution order via `effect-order`**: `:db` runs first so other effects see updated state
- **Volatile self-reference**: async callbacks dispatch back through the same stable closure
- **Pull specs are pure data**: `resolve-pull` returns `{:pattern ... :then ...}`, testable without a browser
- **`make-executor` is the only mode-specific function**: sandbox (SCI in-browser) and remote (HTTP) share everything else
- **Pure `.cljc` state layer**: all transitions are testable on the JVM
- **Explicit prop threading over global dispatch**: clearer data flow for small-to-medium SPAs

This pattern is used in both [flybot-site](https://github.com/flybot-sg/lasagna-pattern/tree/main/examples/flybot-site) and [pull-playground](https://github.com/flybot-sg/lasagna-pattern/tree/main/examples/pull-playground). See also [Pull Playground](https://www.loicb.dev/blog/pull-playground-interactive-pattern-learning-md) for how the sandbox/remote mode abstraction works in practice.
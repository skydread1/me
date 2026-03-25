---
tags:
  - clojure
  - architecture
  - web
  - lasagna-pattern
date: 2026-03-25
repos:
  - [lasagna-pattern, "https://github.com/flybot-sg/lasagna-pattern"]
rss-feeds:
  - all
  - clojure
---
## TLDR

How a pull-pattern API evolved from function-calling patterns to structural data matching, and what the shift from verbs to nouns taught us about API design in Clojure.

## Context

We built our pull-pattern API on [lasagna-pull](https://github.com/flybot-sg/lasagna-pull), a library designed by [Robert Luo](https://github.com/robertluo) that lets clients send EDN patterns to describe what data they want. The core pattern-matching engine is solid. But as we added more resources, roles, and mutation types, we wanted a different model for how patterns interact with data sources and side effects. This article is about the design decisions behind [lasagna-pattern](https://github.com/flybot-sg/lasagna-pattern), the successor stack that replaces the function-calling handler layer while building on the same pull-pattern ideas.

For context on what the new architecture looks like, see [Building a Pure Data API with Lasagna Pattern](/blog/building-a-pure-data-api-with-lasagna-pattern). For the monorepo structure that hosts the libraries, see [Clojure Monorepo with Babashka](/blog/clojure-monorepo-with-babashka).

## The old model: patterns call functions

In lasagna-pull, the core mechanism was `:with`. Patterns contained function calls: `(list :key :with [args])` told the engine to look up `:key` in a data map, call the function stored there, and pass it `args`. Functions returned `{:response ... :effects ...}`.

Here is what a few common operations looked like.

**List all dashboards (read):**

```clojure
{:dashboards
 {(list :role/user :with [])
  {(list :self :with []) [{:title '? :id '?}]}}}
```

The outer `:with` checked authorization. The inner `:with` called a function to list all entries. The vector with map shape `[{:title '? :id '?}]` described which fields to return.

**Read by ID:**

```clojure
{:dashboards
 {(list :role/user :with [])
  {(list :dashboard :with [{:id 123} :read])
   {:title '? :content '?}}}}
```

The `:read` action dispatched inside the function via `case`.

**Create:**

```clojure
{:dashboards
 {(list :role/user :with [])
  {(list :dashboard :with [{:title "New" :content "..."} :save])
   {:id '? :title '?}}}}
```

Same function, different action. The function returned `{:response data :effects {:rama {...}}}`, and a separate executor ran the side effects after the pattern resolved.

On the server, the data map was a nested structure of functions:

```clojure
(defn pullable-data [session]
  {:dashboards
   {:role/user (with-role session :user
     (fn []
       {:dashboard (fn [data action]
                     (case action
                       :read   {:response (get-dash (:id data))}
                       :save   {:response data
                                :effects  {:rama {...}}}
                       :delete {:response true
                                :effects  {:rama {...}}}))}))}})
```

Authorization was a function wrapper: `with-role` took a session, a role keyword, and a thunk that returned the data map. If the role was missing, the thunk never ran.

### The saturn handler: pure by design

This architecture had a name: the "saturn handler" pattern, designed by [Robert Luo](https://github.com/robertluo). The idea was to split request handling into three stages:

1. **Injectors** provided dependencies (DB snapshot) to the request
2. **Saturn handler** (purely functional) ran the pull pattern, accumulated `{:response, :effects-desc, :session}` with zero side effects
3. **Executors** took the effects descriptions and actually ran them (DB transactions, cache invalidation)

The `context-of` mechanism coordinated accumulation during pattern resolution. A modifier function extracted `:response`, `:effects`, and `:session` from each operation result. A finalizer attached the accumulated effects and session updates to the final result. The handler itself never touched the database for writes.

```clojure
;; Saturn handler: purely functional, no side effects
(defn saturn-handler [{:keys [db session] :as req}]
  (let [pattern (extract-pattern req)
        data    (pullable-data db session)
        result  (pull/with-data-schema schema (mk-query pattern data))]
    {:response     ('&? result)
     :effects-desc (:context/effects result)
     :session      (merge session (:context/sessions result))}))
```

This was a clean separation. The saturn handler was fully testable with no mocks. Effects were pure data descriptions. The executor was the only impure component, and it was small. The original implementation is documented in the archived [flybot.sg repository](https://github.com/skydread1/flybot.sg/tree/master/docs).

## What pushed us to redesign

The saturn handler separation was elegant, but as the system grew, specific limitations emerged.

**Response before effects.** The saturn handler computed `:response` before the executor ran `:effects`. This worked when the response data was already known (e.g., returning the input entity on create). But when you needed something produced by the side effect itself (a DB-generated ID, a timestamp set by the storage layer, a merged entity after a partial update), you were stuck. The `f-merge` escape hatch existed: a closing function in the effects description that could amend the response after execution. But using `f-merge` essentially reintroduced in-place mutation, defeating the purpose of the pure/impure split.

**Verb-oriented patterns.** Every pattern was a set of function calls. Reading all items called a function. Reading one item called a different function with a `:read` action. Creating called the same function with a `:save` action. The `case` dispatch inside each `:with` function grew as operations multiplied. The pattern language was supposed to describe data, but it was describing procedure calls.

**Authorization at two granularities.** `with-role` gated access to the entire data map (coarse). But ownership enforcement (can this user edit this specific item?) had to live inside the `:with` function's `case` dispatch (fine). These were two different authorization mechanisms in two different places, with no intermediate layer for "can mutate, but only own entities."

**Indirection through context-of.** The modifier/finalizer mechanism in `context-of` was well-designed for what it did: accumulate effects and session updates during pattern resolution without side effects. But it was a layer you had to understand to trace a request end-to-end. Each operation returned `{:response :effects :session :error}`, the modifier unpacked those, and the finalizer attached the accumulations. The mechanics were sound, but the indirection meant debugging required following the data through several stages.

The saturn handler pattern achieved something valuable: a fully testable, purely functional request handler. The redesign was not about fixing a broken system. It was about recognizing that once collections replaced functions as the API's building blocks, the pure/impure split could happen at a different boundary (inside DataSource methods), and the accumulation machinery was no longer needed.

## The new model: patterns match data

The rewrite inverted the relationship. Instead of patterns calling functions, patterns match against data structures. Collections implement `ILookup` (Clojure's `get` protocol) for reads and a `Mutable` protocol for writes. The pattern engine does not know about functions. It just walks a data structure.

Here are the same operations in the new model.

**List all dashboards:**

```clojure
'{:user {:dashboards ?all}}
```

`:user` is a top-level key in the API map. If the session has the user role, it resolves to a map containing `:dashboards`. If not, it resolves to `nil`. `?all` is a variable that binds to `(seq dashboards)`, triggering `list-all` on the DataSource.

**Read by ID:**

```clojure
'{:user {:dashboards {{:id $id} ?dash}}}
;; client sends: {:pattern ... :params {:id 123}}
```

`{:id $id}` is a lookup key. `$id` gets replaced with `123` before the pattern compiles. The collection's `ILookup` implementation receives `{:id 123}` and delegates to the DataSource's `fetch` method.

**Create:**

```clojure
{:user {:dashboards {nil {:title "New" :content "..."}}}}
```

`nil` as a key means "create". The collection's `Mutable` implementation calls `create!` on the DataSource. The response is the full created entity.

No `:with`, no action keywords, no `case` dispatch. The pattern syntax itself encodes the operation: `?var` means read, `nil` key means create, `nil` value means delete, key + value means update.

On the server, the data map is a structure of collections, not functions:

```clojure
(defn make-api [{:keys [storage cache]}]
  (let [dashboards (coll/collection (->DashboardSource storage cache)
                                    {:id-key :id
                                     :indexes #{#{:id}}})]
    (fn [ring-request]
      (let [session (:session ring-request)]
        {:data   {:user  (when (:user session)
                           {:dashboards dashboards})
                  :owner (when (:owner session)
                           {:users users-collection
                            :roles roles-collection})}
         :schema {:user  {:dashboards [:vector Dashboard]}
                  :owner {:users [:vector User]}}
         :errors {:detect :error
                  :codes  {:forbidden 403 :not-found 404}}}))))
```

## Side by side

The contrast is clearest when you see old and new patterns next to each other.

### Simple read (list all)

```clojure
;; OLD: two nested function calls
{:dashboards
 {(list :role/user :with [])
  {(list :self :with []) [{:title '? :id '?}]}}}

;; NEW: structural traversal
'{:user {:dashboards ?all}}
```

The old pattern needed two `:with` calls just to list everything: one for role checking, one for the listing function. The new pattern walks a data structure. If `:user` exists in the API map, `:dashboards` is a collection, and `?all` binds to its contents.

### Read by ID with parameters

```clojure
;; OLD: function call with arguments
{:dashboards
 {(list :role/user :with [])
  {(list :dashboard :with [{:id 123} :read])
   {:title '? :content '?}}}}

;; NEW: indexed lookup with $params
'{:user {:dashboards {{:id $id} ?dash}}}
;; params: {:id 123}
```

`:with [{:id 123} :read]` called a function and passed it two arguments. `{:id $id}` is text substitution: `$id` becomes `123`, then `{:id 123}` is used as a lookup key on the collection. The difference is that `$params` happens before pattern compilation. There is no function call in the pattern at all.

### Create

```clojure
;; OLD: function call with :save action
{:dashboards
 {(list :role/user :with [])
  {(list :dashboard :with [{:title "New" :content "..."} :save])
   {:id '? :title '?}}}}

;; NEW: nil key = create
{:user {:dashboards {nil {:title "New" :content "..."}}}}
```

The old model used the same function for reads and writes, distinguished by an action keyword (`:read`, `:save`, `:delete`). The new model uses structural conventions: `nil` as the key means create. The collection's `Mutable` protocol handles it.

### Delete

```clojure
;; OLD: function call with :delete action
{:dashboards
 {(list :role/user :with [])
  {(list :dashboard :with [{:id 123} :delete])
   {:id '?}}}}

;; NEW: query key + nil value = delete
{:user {:dashboards {{:id 123} nil}}}
```

`nil` as the value means delete. No action keywords, no function dispatch.

### Analytics query (complex parameters)

```clojure
;; OLD: arbitrary query object via :with
{:analytics
 {(list :raw :with [{:data-source [:module-1 :stats]
                      :select :col-name
                      :time-range {:from "2026-01-01" :to "2026-02-01"}}])
  '?}}

;; NEW: query object as lookup key via $params
'{:user {:analytics {$query ?result}}}
;; params: {:query {:data-source [:module-1 :stats]
;;                  :select :col-name
;;                  :time-range {:from "2026-01-01" :to "2026-02-01"}}}
```

The query object is the same in both cases. The difference is where it lives: inside a function call (old) versus as a lookup key (new). The DataSource's `fetch` method receives the full query map and routes internally.

## What we gained

### Authorization is structural, not functional

Old: `(with-role session :user (fn [] ...))` wraps a thunk. Authorization is a function that gates access to other functions.

New: top-level keys in the API map are `nil` when the session lacks the role. The pattern simply gets `nil` for unauthorized paths. No function call, no wrapper.

```clojure
;; Session has :user but not :owner
{:data {:user  {:dashboards dashboards}   ;; present
        :owner nil}}                       ;; nil: patterns against :owner return nothing
```

For finer-grained checks (ownership enforcement on mutations), `wrap-mutable` intercepts write operations:

```clojure
(coll/wrap-mutable dashboards
  (fn [inner query value]
    (if (owns? session query)
      (coll/mutate! inner query value)
      {:error {:type :forbidden}})))
```

This is still structural: a decorator around a collection, not conditional logic inside a handler.

### $params replaces :with

`:with` called a function with arguments at pattern-resolution time. `$params` does text substitution before the pattern is even compiled.

```clojure
;; $params: symbol replacement before compilation
'{:users {{:id $uid} ?user}}
;; + params {:uid 123}
;; becomes: {:users {{:id 123} ?user}}
```

The pattern engine never sees `$uid`. By the time it runs, the pattern is pure data. This means patterns are always static structures from the engine's perspective, which simplifies the implementation and makes patterns easier to reason about.

### No context-of, modifier, or finalizer

The old `context-of` mechanism was well-engineered: modifier functions extracted `:response`/`:effects`/`:session` from each operation, accumulated them in transient collections, and the finalizer attached them to the result. The saturn handler stayed pure throughout. It was a clean solution to the problem of accumulating side-effect descriptions during pattern resolution.

The new system does not need any of it:

- Side effects happen inside DataSource methods (not returned as data)
- Sessions are managed by Ring middleware (not returned from patterns)
- The pattern result is the final response (no post-processing)

The tradeoff: the saturn handler's strict pure/impure boundary is gone. DataSource methods perform side effects directly, which means the handler is no longer purely functional. In practice, this turned out to be acceptable because DataSource implementations are small, focused, and testable in isolation. The purity moved from the handler level to the collection wrapper level (decorators like `wrap-mutable` and `read-only` are pure transformations).

### Side effects live inside DataSource

Old: functions returned `{:response ... :effects {:rama {...}}}`. The saturn handler accumulated these descriptions. A separate executor ran them afterward. The handler was purely functional.

New: `create!`, `update!`, and `delete!` in `DataSource` perform the side effects directly. The return value is the entity itself, not a description of work to be done.

```clojure
(defrecord DashboardSource [storage cache]
  coll/DataSource
  (create! [_ data]
    (storage-append! storage [data :save])
    (assoc data :id (generate-id) :created-at (now)))
  (delete! [_ query]
    (storage-append! storage [query :delete])
    true))
```

This solves the "response before effects" problem directly: `create!` performs the write and returns the full entity with DB-generated fields. No `f-merge`, no two-phase response construction.

The tradeoff is that the handler is no longer purely functional. If you need the old effects-description pattern for testing, you can wrap the DataSource to capture effects without executing them. But the default path is direct execution, which is simpler to trace.

### Error handling as data

Collections return errors as plain maps:

```clojure
{:error {:type :forbidden :message "You don't own this resource"}}
{:error {:type :not-found}}
```

The `remote` layer maps error types to HTTP status codes via a declarative config:

```clojure
{:detect :error
 :codes  {:forbidden 403 :not-found 404 :invalid-mutation 422}}
```

This keeps collections pure (they return data describing what happened) while the transport layer decides how to represent it. The design is heading toward GraphQL-style partial responses, where one branch failing does not fail the whole pattern. A request for `{:user ?data :admin ?admin-stuff}` should return `:user` data even if `:admin` is forbidden, with errors collected in a top-level array alongside the data.

## Conclusion

The old saturn handler architecture was a genuinely clean design: a purely functional handler, effects as data descriptions, executors as the only impure component. It achieved testability and separation of concerns that many web frameworks do not even attempt.

The redesign was not about fixing something broken. It was about moving the purity boundary. The saturn handler kept the entire request pipeline pure by deferring effects. The new model keeps collections and their wrappers pure by pushing side effects into DataSource methods. The accumulation machinery (context-of, modifier, finalizer) disappears because there is nothing to accumulate. The response-before-effects limitation disappears because `create!` returns the entity directly.

The deeper lesson is about API identity. When your API is a set of handler functions, cross-cutting concerns (authorization, transport, error handling) become imperative code woven through those handlers. When your API is a data structure, those same concerns become structural: the shape of the map enforces authorization, the protocols enforce CRUD semantics, and the transport layer works generically over any `ILookup`-compatible structure.

Verbs become nouns, and the nouns compose.
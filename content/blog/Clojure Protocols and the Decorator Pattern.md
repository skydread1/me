---
tags:
  - clojure
  - architecture
  - lasagna-pattern
date: 2026-03-25
repos:
  - [lasagna-pattern, "https://github.com/flybot-sg/lasagna-pattern"]
rss-feeds:
  - all
  - clojure
---

## TLDR

A tutorial on `defprotocol`, `defrecord`, `deftype`, and `reify` in Clojure, taught through the real-world implementation of a collection library that makes custom types behave like native Clojure data structures. Shows how these constructs compose into a decorator pattern for authorization and data transformation.

## Context

Clojure's built-in functions work on built-in types because those types implement specific Java interfaces. `get` works on maps because maps implement `ILookup`. `seq` works on vectors because vectors implement `Seqable`. `count` works on both because they implement `Counted`.

The interesting part: your custom types can implement the same interfaces. Once they do, Clojure's standard library treats them as first-class citizens. `get`, `seq`, `map`, `filter`, `count` all work transparently, no special dispatch, no wrapper functions.

The [lasagna-pattern](https://github.com/flybot-sg/lasagna-pattern) collection library ([Clojars](https://clojars.org/sg.flybot/lasagna-collection)) does exactly this. It defines a `Collection` type backed by a database, then implements `ILookup` and `Seqable` so that `(get coll {:post/id 3})` triggers a database query while looking like a plain map lookup to the caller. The companion article, [Building a Pure Data API with Lasagna Pattern](/blog/building-a-pure-data-api-with-lasagna-pattern), covers the full architecture. This article focuses on the Clojure constructs that make it work.

## The four constructs

Clojure provides four ways to define types that implement protocols and interfaces. Each serves a different purpose.

### defprotocol: the contract

Defines method signatures with no implementation. Conceptually similar to a Java interface.

```clojure
(defprotocol DataSource
  (fetch [this query])
  (list-all [this])
  (create! [this data])
  (update! [this query data])
  (delete! [this query]))
```

This says: "any storage backend must support these 5 operations." It does not say how. The implementation is left to the types that satisfy the protocol.

### defrecord: named, map-like type

A concrete implementation of a protocol. Has named fields and behaves like a Clojure map (you can `assoc`, `dissoc`, and destructure it).

```clojure
(defrecord PostsDataSource [conn]
  DataSource
  (fetch [_ query]    (d/q ... @conn))
  (list-all [_]       (d/q ... @conn))
  (create! [_ data]   (d/transact conn [data]))
  (update! [_ q data] (d/transact conn [(merge ...)]))
  (delete! [_ query]  (d/transact conn [[:db/retractEntity ...]])))
```

Use `defrecord` for persistent, reusable implementations with named fields: storage backends, services, configuration holders.

### deftype: named, not map-like

Like `defrecord` but without map behavior. Used for structural wrappers that implement platform interfaces rather than domain protocols.

```clojure
(deftype Collection [data-source id-key indexes]
  clojure.lang.ILookup
  (valAt [this q] (.valAt this q nil))
  (valAt [_ q nf] (or (fetch data-source q) nf))

  clojure.lang.Seqable
  (seq [_] (seq (list-all data-source))))
```

Use `deftype` when you need to override built-in Clojure verbs (`get`, `seq`, `count`). The type itself is opaque. Callers interact with it through standard Clojure functions, not through field access.

### reify: anonymous, inline type

Same capability as `deftype` but anonymous and created inline. Closes over local variables.

```clojure
(defn profile-lookup [session]
  (reify clojure.lang.ILookup
    (valAt [this k] (.valAt this k nil))
    (valAt [_ k nf]
      (case k
        :name  (:user-name session)
        :email (:user-email session)
        nf))))
```

Use for one-off objects, per-request wrappers, or cases where a named type would be overkill. The `session` value is captured from the enclosing scope.

### Summary table

| Construct | What it is | When to use |
|-----------|-----------|-------------|
| `defprotocol` | Contract (method signatures) | Define a role: "what must a DataSource do?" |
| `defrecord` | Named type, map-like | Concrete implementations: `PostsDataSource` |
| `deftype` | Named type, not map-like | Structural wrappers: `Collection` |
| `reify` | Anonymous inline type | One-off objects: per-request lookups |

## Overriding built-in verbs

Each Clojure interface corresponds to a built-in verb. Implementing an interface teaches Clojure how your custom type responds to that verb.

### ILookup: powers `get`

When you call `(get thing key)`, Clojure calls `(.valAt thing key nil)` under the hood. Maps implement this by default. Custom types do not.

```clojure
;; Without ILookup
(deftype Box [x])
(get (->Box 42) :x)  ;; => nil (Box doesn't implement ILookup)

;; With ILookup
(deftype SmartBox [x y]
  clojure.lang.ILookup
  (valAt [this k] (.valAt this k nil))
  (valAt [_ k nf]
    (case k :x x :y y nf)))

(get (->SmartBox 1 2) :x)  ;; => 1
```

In the collection library, `ILookup` is what makes `(get coll {:post/id 3})` trigger a database query. The caller writes standard Clojure. The collection translates the `get` call into a `fetch` on the underlying `DataSource`.

### Seqable: powers `seq` (and `map`, `filter`, etc.)

```clojure
clojure.lang.Seqable
(seq [_] (seq (list-all data-source)))
```

Once a type implements `Seqable`, all sequence functions work: `(seq coll)`, `(map f coll)`, `(filter pred coll)`. The collection becomes iterable by delegating to its `DataSource`'s `list-all`.

### Counted: powers `count` directly

```clojure
clojure.lang.Counted
(count [_] (count (list-all data-source)))
```

Without `Counted`, calling `count` on a custom `Seqable` type throws `UnsupportedOperationException`. Clojure's `RT.count()` does not fall back to `seq`. It only works on types that implement `Counted`, `IPersistentCollection`, `java.util.Collection`, or a few other JDK interfaces. If your custom type needs to support `count`, implement `Counted` explicitly. This also lets you provide an optimized path (e.g., a `SELECT COUNT(*)` instead of fetching all rows).

## Custom protocols

The interfaces above override Clojure's built-in verbs. But some operations have no built-in verb. The collection library defines two custom protocols for these cases.

| Protocol | Verb | Purpose |
|----------|------|---------|
| `Mutable` | `mutate!` | Unified CRUD: `(nil, data)` = create, `(query, data)` = update, `(query, nil)` = delete |
| `Wireable` | `->wire` | Serialize for HTTP transport: collections become vectors, lookups become maps or nil |

`mutate!` unifies create, update, and delete into a single function. The operation is determined by the combination of arguments: nil query means create, nil value means delete, both present means update.

`Wireable` is conceptually similar to `clojure.core.protocols/Datafiable` (`datafy`). Both turn opaque types into plain Clojure data. The difference is intent: `datafy` is for introspection and navigation, `->wire` is specifically for HTTP serialization.

## The decorator pattern

Here is the key design insight: one DataSource, one Collection, multiple wrappers per role.

### Without wrappers (bad)

```clojure
;; 3 records duplicating the same Datahike queries
(defrecord GuestPostsDataSource [conn] ...)
(defrecord MemberPostsDataSource [conn] ...)
(defrecord AdminPostsDataSource [conn] ...)
```

Each record contains a full copy of the same fetch, list-all, create!, update!, and delete! logic. Domain logic changes must be applied to all three.

### With wrappers (good)

```clojure
(def posts (db/posts conn))               ;; one DataSource, one Collection

(public-posts posts)                       ;; reify: override get/seq to strip email
(member-posts posts user-id email)         ;; wrap-mutable: override mutate! for ownership
posts                                      ;; admin: no wrapper needed
```

The `DataSource` is created once. Each role gets a thin wrapper that overrides only the behavior it needs. Reads, storage queries, and domain logic live in one place.

### Wrapper functions

| Wrapper | What it overrides | Use case |
|---------|-------------------|----------|
| `coll/read-only` | Removes `Mutable` entirely | Guest access (no writes) |
| `coll/wrap-mutable` | Overrides `mutate!`, delegates reads | Ownership enforcement |
| `reify` (manual) | Override any interface | Transform read results, composite routing |
| `coll/lookup` | Provides `ILookup` from a keyword-value map | Non-enumerable resources (profile, session data) |

### Example: building views per role

```clojure
(let [posts (db/posts conn)]    ;; one record, created once

  {:guest  {:posts (public-posts posts)}            ;; reify over read-only, strips :user/email
   :member {:posts (member-posts posts uid email)}  ;; wrap-mutable, ownership checks
   :admin  {:posts posts}                           ;; raw collection, full access
   :owner  {:users (coll/read-only (db/users conn))}})
```

Guests see a read-only view with PII stripped. Members see a mutable view that enforces ownership. Admins see the raw collection. Each wrapper does one thing.

The `public-posts` wrapper demonstrates how `reify` serves as the escape hatch when the built-in wrappers are not enough:

```clojure
(defn- public-posts [posts]
  (let [inner (coll/read-only posts)]
    (reify
      clojure.lang.ILookup
      (valAt [_ query]
        (when-let [post (.valAt inner query)]
          (strip-author-email post)))

      clojure.lang.Seqable
      (seq [_]
        (map strip-author-email (seq inner))))))
```

The library provides `read-only` (restricts writes) and `wrap-mutable` (intercepts writes), but no built-in way to transform read results. For that, you implement `ILookup` and `Seqable` directly via `reify`.

## Three layers of authorization

Authorization in this pattern is distributed structurally rather than imperatively. Instead of a single middleware that checks permissions, three layers each handle a different granularity.

### Coarse: `with-role` (API map structure)

Binary gate: you have the role or you don't. The entire subtree of collections is present or replaced with an error map.

```clojure
(defn- with-role [session role data]
  (if (contains? (:roles session) role)
    data
    {:error {:type :forbidden :message (str "Role " role " required")}}))

;; In make-api:
:owner (with-role session :owner
         {:users users, :users/roles roles})
```

A non-owner sending `'{:owner {:users ?all}}` hits the error map, not the collection. Before matching, `remote/` walks variable paths over the raw data and trims the pattern at any `{:error ...}` it finds, so the denial surfaces as a `:forbidden` without ever reaching the collection. A plain map is enough, no sentinel object required.

### Medium: `wrap-mutable` (per-entity mutation rules)

Controls who can create, update, or delete specific entities:

```clojure
(coll/wrap-mutable posts
  (fn [posts query value]
    (if (owns-post? posts user-email query)
      (coll/mutate! posts query value)
      {:error {:type :forbidden}})))
```

Reads pass through untouched. Only mutations are intercepted. The check is per-entity: does this user own this specific post?

### Fine: `reify` decorator (field-level read transformation)

Controls which fields are visible:

```clojure
(public-posts posts)  ;; strips :user/email from author on every read
```

Every `get` and `seq` call on this wrapper runs through a transformation function that removes sensitive fields before the data reaches the caller.

### Authorization summary

| Layer | Tool | What it guards | Example |
|-------|------|----------------|---------|
| Coarse | `with-role` | "Can you access `:owner` at all?" | Non-owners get error map |
| Medium | `wrap-mutable` | "Can you mutate this entity?" | Members can only edit own posts |
| Fine | `reify` decorator | "What fields can you see?" | Guests don't see author email |

The DataSource stays "dumb" about authorization. It only knows about storage. This keeps it reusable across all roles without conditional logic.

## When to skip the full stack

Not everything needs `defrecord` + `DataSource` + `Collection`. If a resource is read-only, non-enumerable, and has a single query shape, a raw `reify` implementing `ILookup` + `Wireable` is enough.

Example: a post history lookup that takes a post ID and returns the revision history:

```clojure
(defn post-history-lookup [conn]
  (reify
    clojure.lang.ILookup
    (valAt [_ query]
      (when-let [post-id (:post/id query)]
        (post-history @conn post-id)))
    (valAt [this query not-found]
      (or (.valAt this query) not-found))

    coll/Wireable
    (->wire [_] nil)))  ;; can't enumerate all history
```

The pattern engine still calls `get` on it, so it works identically from the caller's perspective. The full DataSource/Collection stack would add index validation, `Seqable`, `Mutable`, none of which history needs.

### Decision guide

| Need | Tool |
|------|------|
| Full CRUD + enumeration + index validation | `defrecord` + `coll/collection` |
| Read-only, keyword keys, mix of cheap + lazy fields | `coll/lookup` |
| Read-only, map keys, single query shape | Raw `reify` with `ILookup` + `Wireable` |

`coll/lookup` accepts `delay` values for expensive fields: `(coll/lookup {:id uid :slug (delay (db-slug conn uid))})`. Each delay fires at most once whether reached via `get` or `->wire`. For map keys like `{:post/id 3}`, use a raw `reify`.
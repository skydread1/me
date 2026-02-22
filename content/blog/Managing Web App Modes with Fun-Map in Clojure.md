---
tags:
  - clojure
  - architecture
  - dependency-injection
  - fun-map
  - web
date: 2026-02-17
repos:
  - [fun-map, "https://github.com/robertluo/fun-map"]
  - [lasagna-pattern, "https://github.com/flybot-sg/lasagna-pattern"]
---
## TLDR

[Fun-map](https://github.com/robertluo/fun-map) turns Clojure's associative data model into a dependency injection system. A system is a map, components are entries, and different environments (prod, dev, dev-with-oauth) are just `assoc` operations on that map. This article shows how we use it in [flybot-site](https://github.com/flybot-sg/lasagna-pattern/tree/main/examples/flybot-site) to manage three runtime modes with zero conditionals inside components.

## Context

Every non-trivial Clojure application has stateful components (database connections, HTTP servers, caches) that depend on each other and need to start and stop in order. Stuart Sierra framed the problem well in his [Components: Just Enough Structure](https://www.youtube.com/watch?v=13cmHf_kt-Q) talk: stateful resources should not be scattered across namespaces as top-level defs. They need explicit lifecycle management and dependency ordering.

The Clojure community has produced several solutions, each with different tradeoffs:

| Library | System definition | Component definition | Overriding for test/dev |
|---------|-------------------|---------------------|------------------------|
| **Component** | `system-map` + `using` declarations | `defrecord` implementing `Lifecycle` protocol | `assoc` new component, but must still be a record |
| **Integrant** | EDN config map | `defmethod init-key` / `halt-key!` multimethods | Merge config maps |
| **Mount** | Implicit (global `defstate` vars) | `defstate` with `:start`/`:stop` | `with-args`, `with-substitutions` |
| **Fun-map** | Regular Clojure map | `fnk` + `closeable` (plain functions) | `assoc`/`dissoc` on the map |

Component requires every component to be a defrecord. Integrant splits the system across EDN config and multimethods in separate namespaces. Mount ties state to global vars, making parallel testing awkward.

[Fun-map](https://github.com/robertluo/fun-map), created by [@Robert Luo](https://github.com/robertluo), takes a different approach: **the system IS a regular Clojure map**. Values can be plain data or `fnk` functions that declare dependencies on other keys. `life-cycle-map` adds startup/shutdown ordering. That is the entire model.

## The base system

Here is the production system from [flybot-site](https://github.com/flybot-sg/lasagna-pattern/tree/main/examples/flybot-site), a blog platform built on Datahike + http-kit. I have trimmed the logging calls and middleware details for clarity, but the structure is from the actual code:

```clojure
(defn make-base-system [config]
  (let [{:keys [server db auth session init uploads log]} (cfg/prepare-cfg config)
        {:keys [port base-url]} server
        db-cfg    (build-datahike-cfg db)
        session-key (cfg/parse-session-secret (:secret session))]
    (life-cycle-map
     {;;--- Config (plain values, no fnk needed) ---
      ::port          port
      ::base-url      base-url
      ::db-cfg        db-cfg
      ::owner-emails  (:owner-emails auth)

      ;;--- Logger (mulog) ---
      ::logger
      (fnk []
           (let [stop-fn ...]
             (closeable {:log ...} #(stop-fn))))

      ;;--- Database ---
      ::db
      (fnk [::db-cfg ::logger]
           (let [conn (db/create-conn! db-cfg)]
             ...
             (closeable {:conn conn :cfg db-cfg}
                        #(db/release-conn! conn db-cfg))))

      ;;--- API (request -> {:data :schema}) ---
      ::api-fn
      (fnk [::db ::logger]
           (api/make-api {:conn (:conn db)}))

      ;;--- Session (secure cookies for prod) ---
      ::session-config
      (fnk [::logger]
           {:store (cookie-store {:key ...})
            :cookie-attrs {:same-site :lax :http-only true :secure true}})

      ;;--- Upload handler (S3 or local) ---
      ::upload-handler
      (fnk [::logger] ...)

      ;;--- Dev user slot (nil in prod, overridden in dev) ---
      ::dev-user nil

      ;;--- Ring application ---
      ::ring-app
      (fnk [::api-fn ::session-config ::dev-user ::base-url
            ::upload-handler ::logger ::db ::owner-emails]
           (-> (fn [_] ...)
               (remote/wrap-api api-fn {:path "/api"})
               (auth/wrap-google-auth {...})
               (wrap-dev-user dev-user)
               (wrap-session session-config)
               ...))

      ;;--- HTTP Server ---
      ::http-server
      (fnk [::ring-app ::port ::logger]
           (let [stop-fn (http-kit/run-server ring-app {:port port})]
             (closeable {:port port :api-endpoint ...}
                        #(stop-fn))))})))
```

The dependency graph is readable at a glance:

```
life-cycle-map
├── ::port, ::base-url, ::db-cfg, ::owner-emails   (plain config values)
├── ::logger                                         (no deps)
├── ::db                                             (depends on ::db-cfg, ::logger)
├── ::api-fn                                         (depends on ::db, ::logger)
├── ::session-config                                 (depends on ::logger)
├── ::upload-handler                                 (depends on ::logger)
├── ::dev-user                                       (nil in prod)
├── ::ring-app                                       (depends on most of the above)
└── ::http-server                                    (depends on ::ring-app, ::port, ::logger)
```

Three fun-map primitives do all the work:

- **`fnk`**: a function that destructures its dependencies from the map. `(fnk [::db ::logger] ...)` declares that this component needs `::db` and `::logger` to start.
- **`closeable`**: wraps a value with a teardown function. When `halt!` is called, closeables are torn down in reverse dependency order.
- **`life-cycle-map`**: a map that tracks which components have been started. Access any key to trigger its transitive dependency chain. `halt!` stops everything.

```clojure
(def sys (make-base-system prod-config))

;; Start: access any key to trigger its dependency chain
(::http-server sys)
;; => {:port 8080, :api-endpoint "http://localhost:8080/api"}

;; Stop: close all components in reverse dependency order
(halt! sys)
```

## Three modes via assoc

The real payoff is building variant systems. Since the system is a map, different environments are `assoc` operations on the base. No conditionals inside components.

### The nil slot pattern

Notice `::dev-user nil` in the base system. The `wrap-dev-user` middleware checks this value:

```clojure
(defn- wrap-dev-user [handler dev-user]
  (if dev-user
    (fn [request]
      (let [session (merge (:session request)
                           {:user-id      (:id dev-user)
                            :user-email   (:email dev-user)
                            :user-name    (:name dev-user)
                            :user-picture (:picture dev-user)
                            :roles        (or (:roles dev-user) #{:member :admin :owner})})]
        (handler (assoc request :session session))))
    handler))
```

In prod, `dev-user` is nil, so `wrap-dev-user` returns the handler unchanged. It is a no-op, not a conditional. The middleware does not know about modes. It only knows about its input.

### Dev system (skip OAuth)

For local development, we need insecure cookies (no HTTPS) and an auto-login user (skip the Google OAuth flow). Two `assoc` calls on the base:

```clojure
(defn make-dev-system [config]
  (let [{:keys [session dev]} (cfg/prepare-cfg config)
        session-key (cfg/parse-session-secret (:secret session))
        {dev-user-cfg :user} dev]
    (-> (make-base-system config)
        (assoc ::session-config (make-dev-session-config session-key))
        (assoc ::dev-user (make-dev-user-component dev-user-cfg)))))
```

`make-dev-session-config` returns a `fnk` identical to the prod version but with `:secure false`. `make-dev-user-component` creates the user in Datahike and grants roles at startup:

```clojure
(defn- make-dev-user-component [dev-user-cfg]
  (fnk [::db ::logger]
       (when dev-user-cfg
         (let [conn (:conn db)
               {:keys [id name email roles]} dev-user-cfg
               roles (or roles #{:member :admin :owner})]
           (db/upsert-user! conn #:user{:id id :email email :name name :picture ""})
           (doseq [role roles]
             (db/grant-role! conn id role))
           {:id id :email email :name name :picture nil :roles roles}))))
```

The rest of the system (database, API, server, middleware stack) is inherited unchanged.

### Dev with OAuth (test the login flow)

Sometimes we need to test the Google OAuth flow locally but still want insecure cookies. One `assoc`:

```clojure
(defn make-dev-oauth-system [config]
  (let [session-key (cfg/parse-session-secret (:secret (:session (cfg/prepare-cfg config))))]
    (-> (make-base-system config)
        (assoc ::session-config (make-dev-session-config session-key)))))
```

No `::dev-user` override, so it stays nil, and `wrap-dev-user` remains a no-op. The OAuth middleware handles login normally.

### What each mode changes

| Component | Prod (base) | Dev | Dev with OAuth |
|-----------|-------------|-----|----------------|
| `::session-config` | `:secure true` | `:secure false` | `:secure false` |
| `::dev-user` | `nil` (no-op) | Auto-login user with roles | `nil` (no-op) |
| Everything else | Base | Inherited | Inherited |

### Mode dispatch

A single entry point selects the constructor:

```clojure
(defn make-system
  ([] (make-system {}))
  ([config]
   (let [{:keys [mode]} (cfg/prepare-cfg config)]
     (case mode
       :dev             (make-dev-system config)
       :dev-with-oauth2 (make-dev-oauth-system config)
       (make-base-system config)))))
```

## Testing with a fresh system

Tests use the dev mode with an in-memory database. The fixture creates a system, starts it, and tears it down:

```clojure
(def test-config
  {:mode   :dev
   :server {:port 18765 :base-url "http://localhost:18765"}
   :db     {:backend :mem :id "test-blog"}
   :auth   {:owner-emails #{"owner@test.com"}}
   :init   {:seed? false}
   :dev    {:user {:id "owner" :email "owner@test.com" :name "Test Owner"}}})

(defn with-system [f]
  (let [sys (system/make-system test-config)]
    (try
      (touch sys)
      (binding [*sys* sys]
        (f))
      (finally
        (halt! sys)))))

(use-fixtures :once with-system)
```

No special test infrastructure. The system is a value. `touch` starts everything, `halt!` stops it. The in-memory Datahike backend means each test run is isolated. Because there is no global state, you could run multiple systems in the same JVM for parallel testing.

## The pattern across projects

This pattern scales beyond flybot-site. In our internal analytics platform built on Rama, the same structure appears with entirely different components:

```clojure
(defn system
  [{:cfg/keys [external-http rama log analytics-cfg dashboards-cfg oauth2]}]
  (life-cycle-map
   {::logger              (fnk [] ...)
    ::rama-clu             (fnk [::logger] ...)
    ::cache                (fnk [::rama-clu] ...)
    ::oauth2-config        {:google (merge cfg/oauth2-default-config oauth2)}
    ::ring-handler         (fnk [::injectors ::saturn-handler ::executors ::system-monitor ::oauth2-config] ...)
    ::external-http-server (fnk [::logger ::ring-handler] ...)}))
```

Different components (Rama cluster manager, Prometheus metrics, Jetty instead of http-kit), same composition model. The system is a map. Components are entries. Lifecycle is `closeable`.

## Why not the alternatives

**vs. Component**: No defrecords, no `Lifecycle` protocol. Components are functions, not types. You do not need to define a record just to hold a database connection.

**vs. Integrant**: No separation between config (EDN) and implementation (multimethods). The system definition IS the implementation. You see the dependency graph, the startup logic, and the teardown logic in one place.

**vs. Mount**: No global state. You can run multiple systems in the same JVM. Parallel test execution works naturally because each test gets its own system value.

The `assoc` composition also means you never need conditional logic inside components. The production `::session-config` does not check `if dev?`. Instead, the dev system replaces it entirely. Each component does one thing.

## Conclusion

- **A system is a map**: readable, inspectable in the REPL, serializable as data
- **Three modes, zero conditionals**: prod is the base, dev `assoc`s two components, dev-with-oauth `assoc`s one. No `if dev?` inside any component.
- **The nil slot pattern**: `::dev-user nil` in the base lets middleware be a no-op in prod without knowing about modes
- **No framework buy-in**: components are `fnk` functions and `closeable` wrappers, not protocol implementations or multimethod dispatches
- **Partial startup**: access one key and only its transitive dependencies start
- **Same pattern across projects**: flybot-site (http-kit + Datahike) and hibou (Jetty + Rama) use identical composition
---
tags:
  - clojure
  - rama
  - architecture
  - analytics
date: 2025-10-13
rss-feeds:
  - all
---
## TLDR

Hibou is a configuration-driven analytics platform built on [Rama](https://redplanetlabs.com/learn-rama). A single EDN config generates the Rama module, the API validation schemas, and the UI query forms. The platform supports two complementary module architectures: nested PStates for pre-aggregated hot-path queries, and column-based PStates for flexible ad-hoc exploration. This article covers how I got there and what I learned about where Rama fits in the analytics landscape.

## Context

The [POC](https://www.loicb.dev/blog/gaming-stats-aggregation-with-rama-md) proved that Rama could serve as the analytics backend for our gaming platform. But it also revealed three problems:

- **One monolithic module**: tightly coupled to one client's event schema
- **Config drift**: the API validation schemas and the Rama module definition were maintained separately
- **Only rolled-up data**: PStates stored pre-aggregated metrics, with no access to individual records

The next step was to generify the stack into a reusable, configuration-driven platform. That became Hibou (French for "owl", a nod to my roots).

## Architecture

Hibou is organized as a mono-repo with four components:

- **hibou/analytics**: a generic Rama module builder that generates modules from EDN configs
- **hibou/dashboards**: a Rama module for dashboard CRUD (save, edit, delete, query)
- **hibou/api**: authentication, Malli validation, and a pullable API encapsulating all Rama interactions
- **hibou/ui**: a [Replicant](https://replicant.fun/) SPA with ECharts visualizations and a config-driven query builder

A client repo provides EDN config files describing its event schemas and desired aggregations. Hibou generates everything else.

## Configuration as the source of truth

The core design decision was making annotated EDN configs the single source of truth for the entire stack. A config describes:

- Which Kafka topics to ingest from (or Rama native depots)
- How events map to dimensions and metrics
- Which granularities to aggregate by
- Which query topologies to expose

That same config is then reused across three layers:

1. **Module generation**: `defclient-module` produces a complete Rama module from the config
2. **API validation**: Malli schemas are derived directly from the config
3. **UI form building**: the query editor generates its form fields from the config

When the config changes, the module, the API validation, and the UI forms all adapt. No separate schema maintenance. I use namespaced keyword annotations to carry metadata for the API and UI layers without affecting Rama's interpretation of the config.

## Two module architectures

Building the platform, I discovered that no single PState design serves all analytics needs well. I ended up with two complementary approaches. A colleague joined the implementation effort and contributed optimizations to the column-based PState layout and refactoring work that simplified the nested module configs.

### Nested (pre-aggregated)

The first module type stores metrics in deeply nested map structures, aggregated at write time:

```
{granularity -> {bucket -> {dim1 -> {dim2 -> {:metric-a val, :metric-b val}}}}}
```

Reads are fast because the work is done during ingestion. A query walks a known path through the nested map and returns pre-computed results. This is ideal for hot-path dashboards where the dimensions and metrics are known in advance.

The tradeoff is rigidity. The set of dimensions and metrics is fixed at module definition time. If an analyst wants to slice by a dimension that was not anticipated, the module needs to be reconfigured and data re-ingested.

### Column-based (query-time aggregation)

The second module type stores raw event data and performs aggregations at query time. It uses an inverted index for filtering and a column store for value retrieval. Any column can serve as a dimension or a metric, and analysts can explore the data without pre-defined aggregation paths.

This gives much more flexibility. New questions can be answered without module changes. The tradeoff is that query-time aggregation is inherently slower than reading pre-computed results.

### Why both

The two approaches complement each other:

| | Nested | Column-based |
|---|---|---|
| Aggregation | Write time | Query time |
| Read speed | Instant (path lookup) | Depends on data volume and filters |
| Flexibility | Fixed dimensions/metrics | Any column, ad-hoc |
| Raw records | No | Yes |
| Best for | Known hot-path patterns | Exploratory queries |

In practice, I use nested modules for the 2-3 dashboards that game operations teams check daily, and column-based modules for ad-hoc investigation when something unexpected shows up in the data.

## Where Rama fits

Working on Hibou gave me a clear picture of where Rama's architecture is genuinely strong and where it runs into fundamental constraints.

### What Rama does well

**Cross-topic JOINs at ingest time.** Our gaming platform has separate Kafka topics for game scores, account actions, recharges, and logins. Correlating across these topics is a common analytics need ("show me recharge behavior for users who played on beginner servers last week"). In traditional OLAP systems, this means query-time joins across large datasets, which can be expensive. Rama handles this at ingest time: events from different topics are joined under an entity key (e.g. username) during microbatch processing. The joined data lives on one partition, so cross-topic queries are just local reads.

**Entity-scoped stateful computation.** Rama partitions data by entity key. All events for a given user land on the same partition. This makes per-entity logic (running stats, lifetime aggregates, anomaly detection) straightforward. Lifetime metrics survive data rotation because they are maintained as running aggregates, not recomputed from raw events.

**Resource management flexibility.** Rama lets you control how many workers, threads, and partitions each module gets. You can allocate more resources to high-traffic modules and fewer to low-traffic ones. The module deployment model (uberjars deployed to a Rama cluster) means you can scale the analytics layer independently of the API and UI, which run on separate infrastructure.

**Config-driven module generation.** Because Rama modules are defined programmatically in Clojure, I can generate them from config. This is the foundation of Hibou's architecture. The shift from writing Rama code to writing EDN configs is a shift from query-time flexibility to compile-time freedom: instead of supporting arbitrary queries at runtime, I generate purpose-built modules at deploy time.

### The RocksDB constraint

Rama uses RocksDB as its underlying storage engine. RocksDB is a key-value store, optimized for point lookups and range scans over sorted keys. This works well for entity-scoped queries where you know the partition key.

For platform-wide aggregations without entity filters (e.g. "total revenue across all users for the last 30 days"), the query must scan across all partitions. Purpose-built OLAP systems like [Apache Druid](https://druid.apache.org/) and [ClickHouse](https://clickhouse.com/) use columnar storage formats with vectorized execution, which are architecturally optimized for exactly this kind of unfiltered aggregation. RocksDB's row-oriented key-value model is not.

This is not a tuning problem. It is an architectural difference in how data is stored and scanned. For entity-scoped queries, Rama's partitioning model works in your favor. For broad, unfiltered aggregations across the entire dataset, columnar systems have a structural advantage.

## The rest of the stack

### API

Like in the POC, I built a custom API layer rather than using Rama's built-in REST API. `hibou/api` provides a pullable interface using [lasagna-pull](https://github.com/flybot-sg/lasagna-pull). The client sends an EDN pattern describing the data it wants, and the server returns exactly that shape. Malli validates all queries before they reach the Rama cluster, and the API handles authentication via SSO tokens. The frontend never sees Rama internals.

### UI

The query editor generates itself from the Malli schemas derived from the module configs. Users build queries visually with proper validation, and the underlying representation is always pure EDN. Visualizations include time series, bar/pie charts, tables with change indicators, and record-level detail views.

### Dashboards

As described in the [POC article](https://www.loicb.dev/blog/gaming-stats-aggregation-with-rama-md), dashboards are stored in Rama via the `hibou/dashboards` module. A simple depot + PState handling CRUD operations. Keeping dashboards in the same infrastructure as the analytics data avoids introducing another database.

## Client integration

A client repo integrates Hibou by providing EDN config files for its analytics modules. The repo is mostly configuration:

1. Define annotated EDN configs for each module
2. Use `hibou/analytics` to generate and deploy Rama modules
3. Use `hibou/api` to create an authenticated API server
4. Embed `hibou/ui` for the frontend

The mono-repo structure follows the same patterns described in [Clojure Monorepo with Babashka](https://www.loicb.dev/blog/clojure-monorepo-with-babashka-md), with Babashka tasks for building, testing, and deploying each component independently or together.

## What I learned

Building Hibou over the course of a year clarified several things:

- **Config-driven generation pays off.** The investment in making the module builder generic meant that adding a new analytics module for a different event schema is a config file, not a development project.
- **Pre-aggregation and ad-hoc exploration serve different users.** Game operations teams want instant dashboards for known patterns. Analysts want to ask new questions. Both needs are real, and trying to serve them with one PState design leads to compromises.
- **Know your storage engine's strengths.** Rama's RocksDB-based PStates excel at entity-partitioned, stateful computation. They are not columnar stores. Designing queries that work with the partitioning model rather than against it makes a significant difference.
- **Compile-time freedom over query-time flexibility.** Instead of building one system that answers any query at runtime, I generate purpose-built modules at deploy time. Each module is optimized for its specific access patterns. This is a different philosophy from general-purpose OLAP, and it works well when the analytics needs are known and stable.
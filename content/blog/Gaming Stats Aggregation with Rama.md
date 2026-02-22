---
tags:
  - clojure
  - rama
  - kafka
  - architecture
  - analytics
date: 2024-08-12
---
## TLDR

A proof of concept replacing Apache Druid + Imply with an all-Clojure analytics stack: Kafka events ingested by a Rama module, queried through a pullable API, and visualized in a custom dashboard UI. The POC proved the architecture works but revealed the need for a generic, configuration-driven platform.

## Context

Our gaming platform produces a high volume of events across multiple Kafka topics. The existing stack for aggregating and visualizing these stats was [Apache Druid](https://druid.apache.org/) + [Imply](https://imply.io/).

As a Clojure-focused company, we wanted to explore whether [Rama](https://redplanetlabs.com/learn-rama) (Red Planet Labs' distributed stream processing platform) could replace the OLAP layer entirely, keeping the whole stack in Clojure. I led the POC, with a colleague handling the AWS cluster setup and another joining later on the app development.

Rama was still in beta (v0.18.0 when I started), and [Nathan Marz](https://github.com/nathanmarz) was responsive throughout, helping us understand the platform as it evolved.

## Why Rama

Rama combines stream processing and state management in one platform. Instead of Kafka + Flink + a database, Rama handles ingestion, stateful computation, and querying in a single deployment. For our use case, this meant:

- **Kafka integration**: [rama-kafka](https://github.com/redplanetlabs/rama-kafka) connects Rama depots directly to external Kafka topics
- **PStates for storage**: Rama's partitioned state stores hold the aggregated data, no separate database needed
- **Query topologies**: queries run co-located with the data, avoiding network hops for reads
- **Microbatch topology**: simpler fault-tolerance semantics, higher throughput, and more expressive due to batch blocks

## Building the module

The first goal was a Rama module that ingests events from one Kafka topic, pre-aggregates them into a PState organized by time granularity and dimensions, and exposes query topologies to fetch the results. This is a rollup-only approach: metrics are computed at write time and stored in deeply nested maps. Queries return pre-computed aggregates, not individual records. Fast reads, but the set of dimensions and metrics is fixed at module definition time.

I used Rama's In-Process Cluster (IPC) for unit testing, which simulates worker processes as threads in a single JVM. After validating behavior locally, we uberjar'd the module and deployed it on a single-node cluster (multiple processes on one machine) to test actual serialization and partition distribution. The final step was deploying to a multi-node Rama cluster on AWS, set up by a colleague who manages our infrastructure.

## Designing the API

Rama has a [built-in REST API](https://redplanetlabs.com/docs/~/rest.html) for depot appends and PState queries via JSON. We chose to build our own API layer instead, for several reasons:

- **EDN over JSON**: we prefer working with EDN in Clojure
- **Validation**: Malli schemas validate queries before they hit the Rama cluster
- **Encapsulation**: the frontend never needs to know about Rama internals (partitions, PState names, query topology signatures)
- **Authorization**: the API layer handles authentication

We used [lasagna-pull](https://github.com/flybot-sg/lasagna-pull) to represent the entire API as a single pullable EDN data structure. The client sends a pattern describing what it wants, and the server returns exactly that shape. See [Building a Pure Data API with Lasagna Pull](https://www.loicb.dev/blog/building-a-pure-data-api-with-lasagna-pull-md) for how this works in detail.

Since our dashboards always aggregate across multiple partitions (multiple games, multiple users), we interact with Rama exclusively through query topologies (`foreign-query`), never through direct partition lookups (`foreign-select`).

## From Clerk notebook to SPA

A colleague started the frontend as a [Clerk](https://clerk.vision/) notebook, using Reagent and [clerk-sync](https://book.clerk.vision/#clerk-sync) to make it interactive. The notebook hit the API via HTTP POST and rendered results in charts, with the dashboard refreshing every 5 minutes.

This worked well for internal validation but was not suitable for production use. We migrated to a proper SPA using [Replicant](https://github.com/cjohansen/replicant) for rendering and ECharts for visualizations.

## Dashboards in Rama

I initially planned to store user dashboards in Datomic, since we already use it for other applications. A teammate pointed out that since we were already running Rama for analytics, we should commit fully to the platform and store dashboards there too.

The suggestion made sense. The `hibou/dashboards` module is a simple Rama module handling dashboard CRUD via a depot and PState. Writing it took only a few days because the patterns were familiar by that point.

## What the POC proved

At this point we had:
- Two Rama modules: one for analytics (pre-aggregated stats by granularity), one for dashboards
- A pullable API server with authentication encapsulating all Rama logic
- A Replicant SPA with dashboard builder and ECharts visualizations

The POC demonstrated that Rama can serve as the analytics backend for our use case. But it also revealed limitations:

- **One monolithic module**: the analytics module was tightly coupled to one client's event schema
- **Config drift**: the API validation schemas and the Rama module definition were maintained separately
- **Only rolled-up data**: PStates stored pre-aggregated metrics, with no access to individual records

The next step was to generify the stack into a reusable, configuration-driven platform. That became [Hibou](https://www.loicb.dev/blog/hibou-a-generic-analytics-platform-with-rama-md).
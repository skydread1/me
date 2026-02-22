---
tags:
  - clojure
  - java
date: 2024-04-20
repos:
  - [juxt/tick, "https://github.com/juxt/tick"]
---

## TLDR

Time in programming has too many representations, and jumping between them is where bugs live. This article walks through the progression from timestamp to instant using [juxt/tick](https://github.com/juxt/tick), explains why zones and offsets are not the same thing, and shows the DST edge case where adding "one day" gives two different answers depending on whether you use a duration or a period.

## Context

Every project I have worked on eventually hits a time bug. Someone stores a local date-time without a zone. Someone else assumes UTC. A third person adds 24 hours when they meant "next calendar day" and the result drifts by an hour twice a year.

The root cause is always the same: too many representations (`timestamp`, `date-time`, `offset-date-time`, `zoned-date-time`, `instant`, `inst`), each encoding a different level of information. Picking the wrong one at the wrong layer causes subtle, timezone-dependent bugs.

[juxt/tick](https://github.com/juxt/tick) is a Clojure library that wraps `java.time` and treats dates and times as values. The API is composable (pipe with `->`) and the naming is clear. This article uses tick to illustrate the concepts, but the ideas apply regardless of language.

## The progression

Each representation adds one piece of information to the previous:

```
timestamp              1705752000000
    │
    ▼  add human-readable structure
date-time              2024-01-20T12:00
    │
    ▼  add UTC offset
offset-date-time       2024-01-20T12:00+08:00
    │
    ▼  add zone (encapsulates DST rules)
zoned-date-time        2024-01-20T12:00+08:00[Asia/Singapore]
    │
    ▼  normalize to UTC
instant                2024-01-20T04:00:00Z
```

The key insight: **store and compute with instants, display with zoned-date-times.** Everything in between is an intermediate form useful for specific conversions.

## Timestamp

A timestamp is the number of milliseconds (or seconds) since the Unix epoch (1970-01-01T00:00:00Z). It is just an integer like `1705752000000`. Universal and unambiguous, but unreadable by humans. We need a structured representation.

## Local time (date-time)

*Alice is having fish and chips for lunch in London. Her wall clock shows 12pm, her calendar shows January 20th.*

A date-time is a date plus a time-of-day with no zone information. Java calls this `java.time.LocalDateTime`. Tick drops the "local" prefix since time is always local when you read it off a clock:

```clojure
(-> (t/time "12:00")
    (t/on "2024-01-20"))
;=> #time/date-time "2024-01-20T12:00"
```

*At the same moment, Bob is having fish soup at a food court in Singapore. His wall clock shows 8pm.*

Alice and Bob read different times for the same moment. A date-time alone cannot express that they are simultaneous. We need an offset.

## Offset (offset-date-time)

The UTC offset is the difference between local time and Coordinated Universal Time (UTC). The UK sits on the prime meridian at `UTC+0` (written `Z`). Singapore is eight hours ahead at `UTC+8`:

```clojure
;; Alice in London
(-> (t/time "12:00")
    (t/on "2024-01-20")
    (t/offset-by 0))
;=> #time/offset-date-time "2024-01-20T12:00Z"

;; Bob in Singapore
(-> (t/time "20:00")
    (t/on "2024-01-20")
    (t/offset-by 8))
;=> #time/offset-date-time "2024-01-20T20:00+08:00"
```

In Java: `java.time.OffsetDateTime`. The `+08:00` suffix is the UTC offset.

This seems sufficient, but there is a problem: the UTC offset for a given location is not always the same.

## Zone (zoned-date-time)

Alice's offset is `UTC+0` in winter but `UTC+1` in summer:

```clojure
;; Alice in winter
(-> (t/time "12:00")
    (t/on "2024-01-20")
    (t/in "Europe/London")
    (t/offset-date-time))
;=> #time/offset-date-time "2024-01-20T12:00Z"

;; Alice in summer
(-> (t/time "12:00")
    (t/on "2024-08-20")
    (t/in "Europe/London")
    (t/offset-date-time))
;=> #time/offset-date-time "2024-08-20T12:00+01:00"
```

The difference is Daylight Saving Time (DST). In spring, UK clocks move forward one hour. In autumn, they move back. The zone `Europe/London` encodes these rules so the correct offset is derived automatically.

Not all countries implement DST. Singapore has near-constant sunrise/sunset times year-round, so `Asia/Singapore` is always `UTC+8`. Japan could benefit from DST but chose not to adopt it. The point: **a UTC offset is not a zone.** A zone encapsulates the offset plus the DST rules for a region.

```clojure
(-> (t/time "12:00")
    (t/on "2024-01-20")
    (t/in "Europe/London"))
;=> #time/zoned-date-time "2024-01-20T12:00Z[Europe/London]"
```

In Java: `java.time.ZonedDateTime`. A zoned-date-time carries the full picture: date, time, zone (which determines the offset, including DST).

The consequences for Alice and Bob:
- `Asia/Singapore` has a fixed UTC offset year-round (no DST)
- `Europe/London` shifts between `UTC+0` (winter) and `UTC+1` (summer)
- Bob is 8 hours ahead of Alice in winter, 7 hours ahead in summer

## Instant

For storage and computation, we want something simpler. An `instant` is a point on the UTC timeline, independent of any zone. It is the human-readable equivalent of a timestamp:

```clojure
;; Alice at 12pm London
(-> (t/time "12:00")
    (t/on "2024-01-20")
    (t/in "Europe/London")
    (t/instant))
;=> #time/instant "2024-01-20T12:00:00Z"

;; Bob at 8pm Singapore - same moment
(-> (t/time "20:00")
    (t/on "2024-01-20")
    (t/in "Asia/Singapore")
    (t/instant))
;=> #time/instant "2024-01-20T12:00:00Z"
```

Both produce the same instant. 12pm in London and 8pm in Singapore are the same moment in time.

In Java: `java.time.Instant`. The epoch itself:

```clojure
(t/epoch)
;=> #time/instant "1970-01-01T00:00:00Z"
```

**Store instants in your database. Do arithmetic on instants. Convert to zoned-date-time only at the display layer.**

## Displaying time for users

Alice and Bob do not care about instants. Alice wants to see London time, Bob wants Singapore time. Deriving a zoned-date-time from an instant is straightforward:

```clojure
;; Alice's browser
(t/format (t/formatter "yyyy-MM-dd HH:mm:ss")
          (t/in #time/instant "2024-01-20T12:00:00Z" "Europe/London"))
;=> "2024-01-20 12:00:00"

;; Bob's browser
(t/format (t/formatter "yyyy-MM-dd HH:mm:ss")
          (t/in #time/instant "2024-01-20T12:00:00Z" "Asia/Singapore"))
;=> "2024-01-20 20:00:00"
```

## inst vs instant

As a Clojure developer, you will encounter `#inst` literals. These are `java.util.Date` instances, an older class replaced by `java.time` in Java 8. Avoid `inst` when possible, but some libraries still require it. Tick converts between the two:

```clojure
(t/inst #time/instant "2024-01-20T04:00:00Z")
;=> #inst "2024-01-20T04:00:00.000-00:00"

(t/instant #inst "2024-01-20T04:00:00.000-00:00")
;=> #time/instant "2024-01-20T04:00:00Z"
```

## Practical rules

- **Store and compute**: use `instant` (`java.time.Instant`)
- **Display for users**: convert to `zoned-date-time` (`java.time.ZonedDateTime`), then format
- **Legacy interop**: convert with `t/inst` and `t/instant`

## Duration vs period

A `duration` is time-based (hours, minutes, seconds). A `period` is calendar-based (days, weeks, months, years). They are not interchangeable:

```clojure
(t/new-duration 10 :seconds)
;=> #time/duration "PT10S"

(t/new-period 10 :weeks)
;=> #time/period "P70D"

(t/new-period 10 :seconds)
;; Execution error (IllegalArgumentException)
;; No matching clause: :seconds
```

A `day` is the boundary: it can be expressed as either a duration (exactly 24 hours) or a period (one calendar day):

```clojure
(t/new-duration 10 :days)
;=> #time/duration "PT240H"

(t/new-period 10 :days)
;=> #time/period "P10D"
```

This distinction matters because of DST. In London, at 1am on March 31st 2024, clocks spring forward one hour. That calendar day is only 23 hours long:

```clojure
;; Add a period of 1 day (calendar-based)
(-> (t/time "08:00")
    (t/on "2024-03-30")
    (t/in "Europe/London")
    (t/>> (t/new-period 1 :days)))
;=> #time/zoned-date-time "2024-03-31T08:00+01:00[Europe/London]"

;; Add a duration of 1 day (exactly 24 hours)
(-> (t/time "08:00")
    (t/on "2024-03-30")
    (t/in "Europe/London")
    (t/>> (t/new-duration 1 :days)))
;=> #time/zoned-date-time "2024-03-31T09:00+01:00[Europe/London]"
```

The period gives `08:00` because "one calendar day later" preserves the wall clock time. The duration gives `09:00` because 24 physical hours later, the clock has jumped ahead by one hour due to DST. Both are correct answers to different questions.

## Conclusion

- A **zone** encapsulates both the UTC offset and DST rules for a region
- **Instant** is the right representation for storage and computation: zone-independent, unambiguous
- **Zoned-date-time** is the right representation for display: includes the zone so users see their local time
- **Duration** (time-based) and **period** (calendar-based) answer different questions, and DST is where the difference becomes visible
- [juxt/tick](https://github.com/juxt/tick) makes these conversions composable and readable in Clojure, treating time as values you can pipe through `->` chains
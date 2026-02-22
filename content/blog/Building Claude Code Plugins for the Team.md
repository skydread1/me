---
tags:
  - llm
  - cli
  - clojure
  - architecture
date: 2026-02-19
---
## TLDR

How I went from a personal Claude Code setup to a plugin marketplace for a small engineering team, encoding Clojure conventions, REPL workflows, and git practices into shareable skills. I suggested our CEO adopt the Anthropic Team plan, wrote onboarding guidelines, and built the plugins that the whole team and some of our clients' developers now use daily.

## Context

I lead a three-person engineering team. Our stack is primarily Clojure.

In late 2025, I started using Claude Code with a Max plan. It was good at general programming but struggled with our conventions. The code worked but did not look like our code.

After seeing the productivity gains firsthand, I suggested our CEO adopt the Anthropic Team plan. I now manage the seats and wrote internal onboarding guidelines covering security practices, Claude Code setup for Clojure development, and best practices for LLM-generated code. The plugins came next, as a way to encode our conventions so every team member gets them automatically.

## Stage 1: REPL integration

Clojure development is REPL-driven. Claude Code had no way to evaluate expressions in a running process out of the box.

I started with [clojure-mcp](https://github.com/bhauman/clojure-mcp) by [@Bruce Hauman](https://github.com/bhauman), a full MCP server for REPL evaluation. It worked, but consumed significant context tokens and replaced Claude Code's native file tools.

I then switched to Bruce's [clojure-mcp-light](https://github.com/bhauman/clojure-mcp-light), which is not an MCP server but two CLI tools:

- **`clj-nrepl-eval`**, evaluates expressions against a running nREPL from the command line
- **`clj-paren-repair-claude-hook`**, a hook that fixes mismatched delimiters before they hit disk, zero token cost

The paren repair hook solved what Bruce calls the "Paren Edit Death Loop", where Claude spirals trying to fix delimiter errors. The hook catches these silently.

```json
{
  "hooks": {
    "PreToolUse": [
      {
        "matcher": "Write|Edit",
        "hooks": [
          { "type": "command", "command": "clj-paren-repair-claude-hook --cljfmt" }
        ]
      }
    ]
  }
}
```

## Stage 2: Team adoption

I set up my two colleagues with Claude Code and the REPL tools. The impact was immediate, especially for the one already comfortable with our Clojure stack.

But a known limitation surfaced: without explicit convention context, Claude defaults to generic Clojure style. `if` where we prefer `when`, `deftest` where we use rich comment tests, namespaces scoped differently from our rules. I found myself repeating the same corrections in `CLAUDE.md` files across multiple projects, which is what motivated building reusable skills.

## Stage 3: Skills as encoded knowledge

A Claude Code skill is a markdown file that provides context or workflows to Claude:

| Type | Trigger | Use for |
|------|---------|---------|
| **Action** | User invokes via `/plugin:skill` | Workflows: implement, review, commit |
| **Context** | Auto-loaded when description matches | Style guides, framework patterns |

### Context skills

The Clojure style skill loads automatically when Claude detects Clojure work:

```yaml
---
name: clj
description: Clojure code patterns, style conventions, RCT formatting,
  namespaces, and scoping rules. Use when writing or reviewing
  .clj/.cljc files.
user-invocable: false
---
```

It covers: conditional preferences, threading rules, RCT format, namespace scoping (public API surface of 3 or fewer vars). Because `user-invocable` is `false`, it loads silently. The developer does not need to remember to activate it.

### Action skills

Action skills are invoked explicitly and can reference other skills to chain the full knowledge stack. The implementation skill triggers the REPL workflow, loads the Clojure style context, and runs the test suite.

## The description is everything

Claude uses the `description` field to decide whether to auto-load a skill. A vague description like "helps with code" rarely triggers. The pattern that works is the **decision tree**, shared by Claude Code users on Reddit: a capability statement followed by trigger conditions with keywords users actually type.

```yaml
# Bad
description: Helps with code review

# Good
description: Reviews Clojure code changes as a senior engineer,
  checking style, correctness, and design. Use when asked to review
  code, check a PR, review MR, look at changes, or give feedback
  on Clojure code.
```

Even with good descriptions, context skills can still be ignored. To ensure conventions are consistently applied, I created two action skills (`impl` and `review`) that explicitly reference the other Clojure skills in their workflow steps. When a developer invokes `/impl` or `/review`, Claude loads the full knowledge stack. This is more reliable than hoping context skills auto-load on their own.

## Hooks as guardrails

Plugins can bundle hooks that run on tool use events. The paren repair hook runs both before and after writes. It only processes `.clj`, `.cljs`, `.cljc`, and `.edn` files, safe to enable globally.

Hooks can also enforce tool discipline, for example blocking `git` commands in projects that use a different VCS.

## Plugin architecture

Skills are organized into plugins, plugins into a marketplace:

```
plugins-repo/
├── .claude-plugin/
│   └── marketplace.json
└── plugins/
    ├── team-clj/               # Clojure: impl, review, repl, style, cljs
    ├── team-git/               # Git: conventional commits, MR creation
    └── team-rama/              # Rama: dataflow patterns & constraints
```

Plugins can be scoped per user (Clojure style, git workflow), per project (Rama dataflow), or per local override (gitignored). The marketplace lives on our private GitLab. Teammates install with `/plugin marketplace add <git-url>` and plugins update automatically.

## Shared project context with PROJECT_SUMMARY.md

[@Bruce Hauman](https://github.com/bhauman)'s [clojure-mcp](https://github.com/bhauman/clojure-mcp) introduced `PROJECT_SUMMARY.md`, an LLM-friendly file documenting architecture, key files, and patterns. I adapted this as a plugin skill.

After completing a feature or bug fix, the developer updates the project summary. This gives the next session immediate context and doubles as a seed for MR descriptions.

The key property: `PROJECT_SUMMARY.md` is **LLM-agnostic and shared**. Any developer benefits from it regardless of which LLM they use. Combined with plugin skills for conventions and `CLAUDE.md` for project instructions, no single person is a knowledge bottleneck.

| Context layer | Scope | Benefits |
|---------------|-------|----------|
| **Plugin skills** | Team conventions | Every developer using the plugins |
| **PROJECT_SUMMARY.md** | Project state | Every developer on the project |
| **CLAUDE.md** | Project instructions | Every developer on the project |
| **Personal vault** | Broader knowledge | Personal recall across projects |

## Results

The plugins are used daily by the entire team. Some observations:

**Consistency improved immediately.** Context skills load silently and Claude matches our conventions from the first interaction.

**Newer team members ramped up faster.** Having conventions available in every session from day one shortened the onboarding loop significantly, compared to learning conventions through code review feedback alone.

**Not everyone uses the plugins.** Some developers prefer Claude Code's defaults and add their own context ad hoc. The plugins are a tool, not a mandate.

### What did not work perfectly

- **Team auto-prompt** (`extraKnownMarketplaces`) does not always trigger reliably. Some teammates needed to add the marketplace manually.

## Conclusion

- **Start with REPL integration**, [clojure-mcp-light](https://github.com/bhauman/clojure-mcp-light) provides REPL eval and paren repair with zero token overhead
- **Encode conventions as context skills**, auto-loaded silently, no developer action required
- **The description determines activation**, use the decision tree pattern (capability + "Use when..." triggers)
- **Hooks catch what skills cannot**, paren repair, format enforcement, VCS discipline
- **Write onboarding guidelines**, security practices and setup documentation lower the barrier for the whole team
- **`PROJECT_SUMMARY.md` is shared, LLM-agnostic context**, updated after features and fixes, benefits every developer
- **Newer team members ramp up faster**, conventions are available from day one instead of discovered through code review feedback
- **Not everyone needs plugins**, some developers prefer Claude Code's defaults, and that is fine
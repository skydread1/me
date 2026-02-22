---
tags:
  - llm
  - obsidian
  - cli
date: 2026-02-19
rss-feeds:
  - all
---
## TLDR

How I use Claude Code as the primary interface for an Obsidian vault, capturing technical knowledge mid-session with full context, retrieving past notes to inform current work, and keeping vault conventions consistent through a custom plugin.

## Context

Note-taking during development has a context problem. You hit something worth documenting (a debugging insight, a design tradeoff, an API gotcha) and the options are: switch to your notes app and try to recreate the context manually, or lose it entirely.

I use [Obsidian](https://obsidian.md) as my knowledge base. It stores everything: technical notes, blog article drafts, work documentation, email templates. The vault is version-controlled with git, synced across devices with Obsidian Sync, and backed up weekly with Restic to Backblaze B2. I maintain two vaults: one for personal life, one for development. Obsidian's mobile app makes it easy to switch between them, and I use a `Temp.md` scratch pad to dump ideas on the go, even from my phone.

The pairing with Claude Code was obvious from the start. Claude already has the session context: what I just debugged, what tradeoff I just evaluated, what code I just wrote. Instead of context-switching to Obsidian and summarizing from memory, I just tell Claude to write the note directly.

## The setup

The integration requires three pieces of configuration, each at a different scope.

### 1. Vault conventions file (`CLAUDE.md` in the vault)

The vault's `CLAUDE.md` defines the rules: frontmatter format, tag taxonomy, wiki link conventions, directory structure. This file lives at the vault root and is checked into git.

```markdown
# Dev Notes Vault

## Note Conventions

### Frontmatter
Every note requires:
tags:
  - primary-tag
  - secondary-tag
date: YYYY-MM-DD

### Key Rules
- No H1 headers (Obsidian uses filename as title)
- Wiki links: [Note Name](https://www.loicb.dev/blog/note-name) for internal references
- Place technical notes in Notes/
- Place blog drafts in Articles/blog/
```

The tag taxonomy is part of this file. It evolves with the vault, Claude adds new tags when it judges them relevant and updates the taxonomy accordingly. I do not maintain the tags manually.

### 2. Global instructions (`~/.claude/CLAUDE.md`)

This file tells Claude Code where the vault is and to read the conventions before writing:

```markdown
# Dev Notes Vault

When asked to create a note or document learnings, write to:
`/path/to/dev-notes/Notes/`

Read `/path/to/dev-notes/CLAUDE.md` for note conventions before writing.
```

This is a two-line pointer. The conventions live in the vault, not in the global config. This means the vault is the single source of truth for its own rules.

### 3. Additional directories (`~/.claude/settings.json`)

Claude Code needs file access to the vault directory:

```json
{
  "additionalDirectories": ["/path/to/dev-notes"]
}
```

This grants any Claude Code session, regardless of which project it is running in, read and write access to the vault. A session debugging a web app can write a note. A session reviewing a Kafka integration can reference a previous note on consumer group rebalancing.

## Writing notes mid-session

The typical flow: I am deep in a session (debugging a deployment, designing a data model, refactoring a module) and something clicks. A pattern worth remembering, a gotcha that cost me time, a decision with non-obvious reasoning.

I say something like: "write a note about what we just figured out with the S3 lifecycle policies" or "capture this Rama partitioning insight."

Claude has the full session context. It knows:

- What problem I was solving
- What approaches I tried and rejected
- What code I wrote
- What error messages I hit
- What the working solution looks like

The resulting note is more precise than what I would write manually. When I switch to Obsidian to write notes by hand, I inevitably lose details. I compress the debugging journey into a vague summary, forget which error message led to the insight, skip the code example that made it click.

Claude does not lose those details. The note it produces includes the exact code snippet, the specific error, the reasoning chain. And it formats everything according to vault conventions: proper frontmatter, wiki links to related notes, the right tags.

### When I don't write notes

Not every session produces a note. If I am doing routine work (fixing a typo, updating a dependency, running a deployment I have done before), there is nothing to capture. The bar is: would I want to find this information in six months?

## Retrieving knowledge

The vault is not write-only. I regularly ask Claude to search my notes for past decisions and patterns.

Some examples:

- **Reference during implementation**: "Check my notes on how I set up the OIDC trust policy for GitHub Actions", then Claude reads the relevant note and applies the pattern to the current task.
- **Decision context**: "What did I decide about the Datahike storage backend and why?", then Claude finds the note, summarizes the tradeoffs, and I can confirm or revisit the decision.
- **Framework patterns**: "Read my note on Rama PState operations before writing this topology", feeding Claude the domain-specific knowledge it needs for the current session.

This turns the vault into something closer to a personal knowledge API. The notes I write during one session become context for future sessions. The loop compounds: better notes produce better future work, which produces better notes.

## The note-writing plugin

I built a small Claude Code plugin with a single skill (`note`) that codifies the note-writing workflow. It is a local plugin, not shared with anyone, just part of my personal Claude Code setup.

The skill is a `SKILL.md` file with the workflow encoded as instructions:

```yaml
---
name: note
description: Creates or updates notes in the Obsidian vault following
  vault conventions. Use when asked to write a note, document learnings,
  capture knowledge, update a note, or save something to the vault.
argument-hint: [topic or note name]
---
```

The skill body specifies:

1. **Read conventions first**, always read the vault's `CLAUDE.md` before writing.
2. **Search before creating**, look for existing notes on the topic. Update if found, create new if not.
3. **Follow format rules**, frontmatter with tags and date, no H1 headers, wiki links for internal references.

The description field is deliberate. It includes trigger keywords ("write a note", "document learnings", "capture knowledge", "update a note", "save something to the vault") so Claude auto-activates the skill when I use any of those phrases. Without these keywords, the skill would load less reliably.

### Why a plugin instead of just CLAUDE.md

The global `CLAUDE.md` already points Claude to the vault. The plugin adds two things:

1. **Discoverability**, the skill appears in `/skills`, making it visible and invocable explicitly.
2. **Structured workflow**, the skill enforces the search-before-create pattern. Without it, Claude sometimes creates a new note when an existing one should be updated.

The plugin is lightweight: one skill, one file. But it makes the difference between Claude mostly following conventions and always following them.

## Personal vault vs shared project context

The Obsidian vault is personal knowledge, covering broader patterns, abstract insights, debugging techniques that span projects. But it is not the only place context lives.

Each project has its own `PROJECT_SUMMARY.md` (an LLM-friendly file documenting architecture, key files, and recent changes) and `CLAUDE.md` (project-specific instructions). These are shared, checked into git, and benefit every developer on the team regardless of whether they use an Obsidian vault or even the same LLM.

When I write a vault note about a Rama partitioning insight, that knowledge helps me across projects. When I update a project's `PROJECT_SUMMARY.md` after implementing a feature, that knowledge helps the next person who opens a Claude Code session on that repo. Both are valuable; they operate at different scopes.

The vault captures the kind of knowledge that does not belong in any single repo: cross-project patterns, framework comparisons, tooling workflows, personal decision records. The project-level files capture what this specific codebase does right now. Together, they mean I am not a knowledge bottleneck for my team.

## "But you should write notes yourself"

A common objection: if you do not write the notes by hand, you do not internalize the knowledge. The act of writing is the learning.

I disagree. My notes are not a study tool. They are context for two readers: future me and Claude. When I revisit a topic six months later, I need the precise details (the exact error, the config that fixed it, the tradeoff reasoning), not the fuzzy memory of having once typed them out. And when Claude needs domain-specific knowledge for a current session, it needs accurate, well-linked notes it can read, not a vague summary I half-remember writing.

That said, I do not blindly accept what Claude writes. Every time I commit changes to the vault, I review the notes. I regularly ask Claude to adjust phrasing, restructure sections, or add missing context. The workflow is collaborative: Claude writes fast and links thoroughly, I review and steer. The result is notes that are more complete than what I would write manually, produced in a fraction of the time.

## Obsidian with zero plugins

My Obsidian installation has zero community plugins. No Dataview, no Templater, no daily notes automation. Obsidian is just a visual layer: a nice editor with a graph view that shows how notes connect.

The real work (writing, linking, tagging, searching) happens through Claude Code. Claude handles the wiki links between notes, picks appropriate tags from the taxonomy, and places files in the right directory. I get the graph and the backlinks panel for free just by having well-linked markdown files.

This means the vault is fully portable. The files are plain markdown with YAML frontmatter and wiki links. If I ever wanted to leave Obsidian, I would just replace `[Note Name](https://www.loicb.dev/blog/note-name)` with standard markdown links and everything still works. There is no lock-in to a plugin ecosystem, no custom syntax, no database.

I still use Obsidian because the mobile app is excellent. I can switch between my personal and dev vaults on my phone, dump an idea into `Temp.md` while commuting, and pick it up later from my desk. But the vault does not depend on Obsidian features. It depends on markdown and Claude Code.

## What changed

Before this setup, my note-taking was sporadic. I would finish a session, think "I should document that", and either write a rushed note that missed key details or skip it entirely. My vault had gaps, entire projects with no notes because the friction of switching contexts was just high enough to discourage it.

Now, notes happen as a natural part of the development flow. I just chat with Claude and a note appears in seconds, properly tagged and linked. The vault has better coverage, more precise code examples, and consistent formatting. The wiki links that Claude adds, connecting a new note to related existing notes, build a graph structure that makes the vault more useful as it grows.

The retrieval side matters just as much. Past notes are not just an archive, they are active context for future sessions. The investment in writing a good note pays off the next time I need that knowledge, because Claude can find it and apply it without me having to remember where I put it.

## Conclusion

- **Session context is the key advantage**, Claude has full context of what you just did, making notes more precise than manual summaries
- **Three-layer config**: vault conventions in `CLAUDE.md`, global pointer in `~/.claude/CLAUDE.md`, directory access in `settings.json`
- **`additionalDirectories`** lets any project session write to the vault without being in the vault's directory
- **A small plugin** codifies the note-writing workflow (search before create, format rules, directory placement)
- **Notes are for two readers**: future you and Claude. Precision matters more than the act of handwriting them
- **Review, do not rubber-stamp**, commit reviews keep the vault accurate. Claude writes fast, you steer
- **Zero Obsidian plugins**, Claude Code does the heavy lifting (writing, linking, tagging). Obsidian is just a visual layer and a mobile app
- **Fully portable**, plain markdown with wiki links. No plugin lock-in, no custom syntax
- **Retrieval completes the loop**, past notes become active context for future sessions, compounding the value
- **Personal vault complements shared project context**, `PROJECT_SUMMARY.md` and `CLAUDE.md` serve the team; the vault captures broader, cross-project knowledge
- **Not every session needs a note**, the bar is whether you would want to find it in six months
# Changelog

All notable changes to www.loicb.dev are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.7.0] - 2026-09-17

### Added

- Two articles: testing the MAGIC compiler against 33 real libraries, and running rich comment tests on the CLR.

### Changed

- Refreshed the Making Magic Stable and Drift Checks articles.

## [0.6.2] - 2026-08-24

### Changed

- Refreshed the Making Magic Stable and Drift Checks articles.

## [0.6.1] - 2026-07-04

### Fixed

- Repo links under a post ran into each other. They now sit apart, each with a GitHub icon.

## [0.6.0] - 2026-07-03

### Changed

- Refreshed the articles and renamed the Hibou post.

### Fixed

- `bb import-notes` publishes imported media to the public assets directory, so images render in dev.
- The import skips code fences when it strips vault-internal references.

## [0.5.0] - 2026-07-01

### Changed

- The git workflow articles draw their diagrams with mermaid.

### Fixed

- A wide table scrolls inside its own box instead of pushing the page sideways.

## [0.4.0] - 2026-06-27

### Added

- ` ```mermaid ` code blocks render as diagrams. Toggling the theme redraws them.
- Two articles on MAGIC stability.

## [0.3.0] - 2026-05-13

### Added

- Anchor links on article headings.
- A dotfiles article.

### Changed

- Polished the CSS.

### Fixed

- The `posts-data` macro reads the filesystem on every build, so an edited post reaches the browser without a clean.

## [0.2.0] - 2026-03-25

### Added

- Faceted tag filtering. Project tags and topic tags render in two rows, and selected tags combine with AND.
- A table of contents on blog posts, with anchor links and back-to-top.
- Profile highlights, with the years of experience computed from a start date.
- Two articles, and project tags across the imported ones.

## [0.1.3] - 2026-03-01

### Fixed

- Wiki-link conversion skips code blocks and drops the `.md` extension. Articles reimported with the corrected links.

## [0.1.2] - 2026-02-23

### Fixed

- RSS feeds render GFM tables.

## [0.1.1] - 2026-02-22

### Fixed

- The logo and favicon reach `dist/`, so the deployed site shows them.

## [0.1.0] - 2026-02-22

### Added

- Portfolio SPA with a blog, RSS feeds, tag links, a sticky header and a version footer.
- Articles imported from the Obsidian vault.
- CI and deploy workflows.

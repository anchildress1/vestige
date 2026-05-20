# ADR-017 — ObjectBox is the Entry Source of Truth

**Status:** Accepted
**Date:** 2026-05-20
**Deciders:** Ashley (sole owner). AI implementors read this as authoritative.
**Supersedes:**
- ADR-001 storage clause "ObjectBox (structured) + markdown source-of-truth..." (see ADR-001 Addendum 2026-05-20)
- The "Markdown Entry Shape" source-of-truth contract previously described in `architecture-brief.md` (since rewritten by this ADR)

---

## Context

The prior design stored each entry twice: once as an ObjectBox row and once as a markdown file in app storage.

That created a pointless sync contract. The app reads ObjectBox for history/detail/patterns, while export needs markdown only at the moment the user asks for a zip.

Two durable copies for one entry is not privacy. It is a bug nursery.

---

## Decision

ObjectBox is the internal source of truth for entries.

- `EntryStore` writes ObjectBox only.
- `EntryEntity.markdownFilename` remains as a stable export filename/reference.
- Settings export renders markdown from ObjectBox rows on demand.
- The export zip contains generated `entries/*.md` files plus `vestige-export.json`.
- Delete-all clears ObjectBox and removes any legacy markdown sidecars left by older debug builds.

---

## Consequences

- No dual-write path.
- No markdown/ObjectBox conflict policy.
- No internal markdown import/edit support in v1.
- Export output stays human-readable without carrying a second internal datastore.

# Brand marks

Candidates for the project's official mark. **Not final** — pick one, then produce the derived assets
listed at the bottom.

| File | Concept | Verdict |
|---|---|---|
| `mark-knot.svg` | Two lines crossing and bound — "rigged together" | **Recommended.** The only candidate that stays legible at 16px, and it reads as a mark rather than as a letter. |
| `mark-shackle.svg` | A shackle: the hardware that joins loads and takes the strain | More thematic and more memorable as an object, but at favicon size the pin starts merging into the bow. |

Two earlier attempts were discarded rather than shipped as filler:

- **rigging/mast** — lines fanning from a spar to anchored nodes. Conceptually the best fit for an
  orchestrator, but built from too many thin strokes: at 16px it turned to mush and read as an
  antenna.
- **R monogram** — perfectly legible at every size, and rejected for exactly that reason: it is just
  a letter in a box, which is the criticism of the current placeholder.

## Two things to know before editing these

**`currentColor` only inherits in inline SVG.** These files use it so an inline mark picks up the
surrounding text colour and works in both themes. Referenced through `<img>` or used as a favicon
they render **black**, so any such use needs a colour-explicit copy.

**Judge marks at real sizes, not in the editor.** Render each candidate at 16 / 24 / 40 / 72px on
light and dark, plus once reversed inside a filled tile. Every problem above was invisible in the
source and obvious in that grid.

## Still to produce, once a mark is chosen

- Colour-explicit variants for `<img>`/favicon use (brand teal, and white for reversed tiles).
- `favicon.svg` plus a raster fallback, replacing the Angular default `favicon.ico`.
- A horizontal lockup (mark + "Rigger" wordmark) for the shell header and the login page, which
  currently render a letter in a rounded square.

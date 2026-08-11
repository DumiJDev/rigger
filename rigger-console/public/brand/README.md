# Brand marks

`mark-knot` is the project's mark. The derived assets are produced and in use.

| File | Use |
|---|---|
| `mark-knot.svg` | **Inline SVG only.** Uses `currentColor`, so it takes the surrounding text colour and works in either theme without a second file. |
| `mark-knot-on-light.svg` | `<img>` on light surfaces. brand-600 `#7A40DA`. |
| `mark-knot-on-dark.svg` | `<img>` on dark surfaces. brand-300 `#C1A9FF`. |
| `lockup-on-light.svg` | Mark + wordmark, light surfaces. Used on the login page. |
| `lockup-on-dark.svg` | Mark + wordmark, dark surfaces. Used in the masthead, which is dark in both themes. |
| `../favicon.svg` | The mark reversed out of a filled violet tile. `../favicon-32.png` and `../favicon-180.png` are rasterised from it. |
| `mark-shackle.svg` | Not used. Kept as the runner-up: more thematic as an object, but at favicon size its pin merges into the bow. |

Two earlier candidates were discarded rather than shipped as filler: a **rigging/mast** mark, built
from too many thin strokes so that at 16px it turned to mush and read as an antenna; and an **R
monogram**, perfectly legible at every size and rejected for exactly that reason — a letter in a box
is the criticism the mark exists to answer.

## Four things to know before editing these

**`currentColor` only inherits in inline SVG.** Referenced through `<img>` or used as a favicon, a
`currentColor` mark renders **black**. That is why the colour-explicit copies exist. If you change
`mark-knot.svg`, change all four derived files with it — nothing generates them.

**The two variants are not the same violet, on purpose.** brand-600 on white, brand-300 on graphite.
One value cannot carry both: brand-600 goes muddy on the masthead and brand-300 washes out on white.
Matching the token each surface already uses is what makes them read as one mark rather than two.

**An XML comment cannot contain `--`.** Writing a CSS custom property name like `--masthead-text` in
a comment silently invalidates the whole file, and the browser renders a broken-image icon with no
console error. Both lockups shipped that way for one render cycle. Write the token name without the
leading dashes.

**The favicon carries its own background.** A favicon has no idea what it sits on: browser tab strips
are light in one theme and near-black in the other, so a stroked mark on transparent either vanishes
or greys out. Hence the filled tile with the mark reversed in white.

## Judge marks at real sizes, not in the editor

Render every change at 16 / 20 / 24 / 40 / 72px on light and dark, plus the lockup at the heights it
is actually used (h20 masthead, h36 login), and **look at the image**. Every problem listed on this
page was invisible in the source and obvious in that grid — including the broken lockups, which
looked like perfectly good XML.

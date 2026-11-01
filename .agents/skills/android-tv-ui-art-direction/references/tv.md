# Android TV / Google TV: 10-foot design reference

## Platform rule
TV is a **focus-first, remote-driven interface for viewing at distance**. Design screens specifically for TV, even when the content/data comes from the same mobile app.

- Use Compose for TV and the `androidx.tv:tv-material` library with `androidx.tv.material3` components where available. Do not blindly import mobile `androidx.compose.material3` components into TV screens or nest unrelated Material themes: behavior and visual tokens can conflict.
- Verify the project's Compose/TV library compatibility before modifying dependencies.
- Use focus-capable TV components as the default for cards, buttons and list items, with a deliberate custom focus treatment when necessary.

## Art direction

- The content is the hero: artwork, cover/poster, player frame, photography, or the product's primary data. A cinematic backdrop is useful **when there is relevant imagery**; it should not be simulated with random blobs.
- Design to be read from roughly 3 meters away: strong titles, short metadata, high contrast, restrained on-screen copy, generous gaps and clear active states.
- Establish at least two compositional depths: a dominant feature/content zone plus a secondary browse/action zone. This is a choice, not a mandate for every settings screen.
- Use 16:9 as the primary canvas. Android's TV design reference uses 960x540 at mdpi. Protect important interactive content with ~5% edge-safe spacing (about 48dp horizontally, 27dp vertically at that reference size); backgrounds may extend to screen edges. Use actual system/inset behavior and test on devices.
- Prefer cinematic typography, coherent image crops, precise grid/row rhythm and intentional negative space over glossy panels.
- Focus states may use outline, tonal surface change, restrained scale or elevation; do not rely on color alone. Avoid scale that clips cards or overlaps neighboring content.

## Navigation and focus: required

1. Every screen has a predictable **initial focus target**. A visible interactive item must be focused when the user first enters, including after loading data asynchronously.
2. Directions Up/Down/Left/Right traverse meaningful 2D neighbors. Avoid focus traps, unpredictable jumps and off-screen focused items.
3. Pressing Select/Enter activates the focused control. Back first closes overlay/dialog or returns to the previous route. Don't hijack Back casually.
4. Restore focus and scroll position when returning from details to a browse row/grid. Check `Modifier.focusRestorer()` and `Modifier.focusGroup()` where compatible with the Compose version.
5. When a drawer opens, focus enters it; closing it returns focus to the invoking context. In dialogs and search overlays, focus should not escape behind the modal.
6. A focused card gets a clear visual reaction within a short, purposeful transition. Test pressed, selected, disabled and loading states too.
7. Never assume touch hover or mouse click as the only test method. Use emulator D-pad keys and, when available, a real remote.

Potential tools (verify against installed Compose version): `FocusRequester`, `focusRequester`, `focusProperties`, `focusGroup`, `focusRestorer`, `onFocusChanged`, TV card defaults. Request initial focus once an enabled, composed target exists rather than on every recomposition.

## TV content patterns

- **Catalog**: hero or recommendation + category rows, stable focus memory, consistent card aspect ratio within a content group; avoid infinite identical sections.
- **Details**: strong image/title, tightly edited metadata, visually dominant Play/Continue action, useful secondary actions, sensible focus target.
- **Player**: artwork/video dominates; controls visible when needed; D-pad play/pause/seek and Back tested. Use Media3 APIs verified for the project version instead of guessing.
- **Settings**: favor simple, readable lists and focused controls; no forced cinematic backdrop if it hurts scanability.
- **Search**: keyboard interactions must be operable from remote; preserve focused result on return.

## TV QA: run this interaction script

- Launch with D-pad only. Can the user tell what is focused?
- Move Right repeatedly across a row, Down into the next group, Left back, Up to the hero/nav, then Select.
- Open details and Back: does the old row and item receive focus again?
- Open an overlay/drawer and close it with Back: is focus restored to the previous context?
- Repeat with empty/loading/error content, long metadata and no artwork.
- Check title/description from a living-room viewing distance and verify no clipping near screen edges.

## What not to do

- Reuse mobile `Scaffold` + `NavigationBar` as a TV home screen.
- Hide focused state behind decorative imagery.
- Generate cards too small or text too dense for distance.
- Use 5 simultaneous focus animations/shadows/glows.
- Claim TV is tested because a touchscreen preview looks good.

Official references:
- https://developer.android.com/training/tv/playback/compose
- https://developer.android.com/design/ui/tv/guides/styles/layouts
- https://developer.android.com/design/ui/tv/guides/styles/focus-system
- https://developer.android.com/develop/ui/compose/touch-input/focus/focus-restoration

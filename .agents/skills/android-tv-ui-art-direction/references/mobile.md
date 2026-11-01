# Native Android mobile/tablet reference

## Goal
Build an intentional, content-led Android UI that retains platform affordances. Compose and Material 3 are implementation systems, not an excuse to ship an uncustomized screenshot of a component catalog.

## Visual composition

- Choose the screen's visual anchor based on its job: editorial headline, content artwork, strong data visualization, form/action, or a concrete product detail.
- Group content by task and meaning rather than wrapping every section in a Card.
- Make typography do work: clear title/section/body/metadata hierarchy and line length control. Vary weight, tracking and size intentionally; do not fill space with headings that say nothing.
- Use semantic colors (surface, surfaceVariant, primary, onSurface, outline, success/error as needed). Keep a meaningful ratio between background, dominant brand color and accents. Check contrast.
- Prefer one defining composition gesture (asymmetric title, edge-to-edge artwork, editorial list, precise grid, distinctive CTA placement) over many decorations.
- Respect existing brand fonts and licensing. If no custom font is installed, compose a strong hierarchy from available typography instead of inventing a font asset.
- Respect edge-to-edge, status/navigation insets, IME, device cutouts and responsive widths.

## Compose implementation

- Use `androidx.compose.material3` components and theme. Consider available Material 3 Expressive APIs when they add real value; verify support in the installed dependency version and opt-in annotations.
- Use adaptive breakpoints/window size classes as appropriate. Avoid hardcoded phone-only widths on tablets and foldables. A tablet composition may need multiple panes, not just stretched cards.
- Keep state outside deeply nested visual composables; model `Loading`, `Content`, `Empty`, `Error` explicitly as appropriate. Use stable keys in lazy lists.
- Support TalkBack semantics/content descriptions for non-text controls, meaningful labels, and touch targets of at least 48dp where feasible.
- Support font scaling, locale variation, dark/light where applicable and keyboard/IME interactions.
- Use real icon vectors/resources installed in the repo. Don't use arbitrary Unicode emoji as icons unless explicitly part of product language.
- Only add custom gesture/motion if it supplements, rather than obstructs, normal Android navigation. Respect accessibility preferences where practical.

## Mobile QA

- Test a compact phone (~360dp width), common phone (~412dp width) and a wider layout when relevant.
- Check content insets, dynamic text, long titles, empty content, loading progress, errors, disabled actions and screen reader labels.
- Make the primary CTA reachable without a scavenger hunt. Scroll position and Back behavior should be predictable.
- Inspect an actual screenshot/preview where tooling permits. If no screenshot was captured, say so.

## Mobile failure examples

- A functional player screen replaced by a three-card “music dashboard.”
- An account settings page redesigned as a promotional hero, hiding the settings.
- A fake shimmer over all content while the loading state already exists.
- Accent colors repeated on every text label so no element looks primary.

Reference: https://developer.android.com/develop/ui/compose/designsystems/material3

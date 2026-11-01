---
name: android-tv-ui-art-direction
description: Design, build, refactor or review distinctive production-quality native Android and Android TV interfaces using Kotlin and Jetpack Compose. Use for mobile screens, Android TV/Google TV 10-foot layouts, themes, navigation, visual polish, UX audits, and removing generic AI-looking UI. Enforces platform-specific behavior, original art direction, accessible interaction and screenshot-based iteration.
---

# Android + TV UI Art Direction

You are a **product designer with strong visual taste and an expert Android Compose engineer**. Build native, usable, memorable interfaces. Do not equate “modern UI” with gradients, rounded cards, or generic Material samples.

## Outcome, not just code

The delivered UI must have: (1) an identifiable visual point of view, (2) clear information hierarchy, (3) native behavior on the target device, (4) believable content and states, and (5) code that fits the existing project.

This skill applies when designing, implementing, polishing, or auditing Android phone/tablet/TV UIs. It is **not** a blanket instruction to redesign unrelated code. Follow the user's specific visual direction when provided.

## 1. Inspect before drawing

- Read the project's Kotlin/Compose files, navigation, version catalog, theme/tokens, dependencies, assets, and existing UI components. Look for platform-specific source sets and app modules.
- Determine the target: **mobile**, **TV**, or **both**. If ambiguous but the project clearly targets one, infer it. If both, design two interaction/layout variants; share domain/state/data code where practical, not blindly the same UI components.
- Identify the actual user task, top 3 content priorities, primary action, real data states, and any existing brand constraints. Preserve established good patterns unless asked to redesign.
- Check API availability against the project's installed versions. Never invent Compose functions, dependencies, image assets, or Gradle coordinates. Do not upgrade libraries merely for style.
- When tools permit, inspect screenshots or previews of the current app before changing it.

## 2. Create a visual direction (internally, then execute)

Before writing UI, settle these five decisions, and briefly communicate them if useful:

1. **Concept**: one sentence describing the app's visual personality, tied to its content (not “clean and modern”).
2. **Hierarchy**: what receives attention first, second, and last.
3. **Distinctive signatures**: choose **two** intentional characteristics from typography, composition, palette, imagery, density, interaction, or motion. They must be specific to this app.
4. **System**: name semantic color, type, space, shape and motion tokens, including screen-specific exceptions.
5. **Behavior**: define the primary interaction and its loading, empty, error, disabled and success states.

Possible directions, **not presets**: editorial typography + restrained color; immersive artwork + cinematic metadata; precise industrial utility + dense legible information; playful high-contrast geometry + restrained motion. Select based on the product, not at random. Do not repeatedly generate the same look for unrelated apps.

If the request gives no brand direction, take a defensible creative decision; don't stop merely to ask about colors.

## 3. Anti-generic UI rules

These are warnings against automatic defaults, **not absolute bans** when a product genuinely needs them:

- No obligatory purple-to-blue gradients, cyan glow, floating glass panels, or giant gradient CTA buttons.
- No “card soup”: don't wrap every label, setting, metric and list item in a similarly rounded elevated rectangle. Use lists, separators, typography, space and surfaces intentionally.
- No stock dashboard composition: three KPI cards followed by feature cards; no interchangeable landing-page hero transplanted into native Android.
- No identical 16dp spacing everywhere, identical large radii on every surface, or centering every block. Use a coherent scale with rhythm and emphasis.
- No fake product claims, random avatar clusters, meaningless charts, decorative emoji, placeholder lorem ipsum in a “finished” UI, or icons with ambiguous actions.
- No animation merely to advertise animation; motion must convey orientation, state or hierarchy. Respect reduced-motion settings when feasible.
- Avoid over-polishing peripheral elements while the core task remains unclear.

**Do** use authentic content shape: long titles, empty data, images with varying aspect ratios, localized text, errors and loading. Choose one strong hero idea rather than stacking many effects. A beautiful native screen should be recognizable in grayscale through composition and typography alone.

## 4. Choose the correct platform reference

- **Mobile / tablet**: read [references/mobile.md](references/mobile.md). Use Jetpack Compose + `androidx.compose.material3` and Material 3 Expressive patterns only when supported and appropriate.
- **Android TV / Google TV**: read [references/tv.md](references/tv.md). Prefer `androidx.tv.material3` and focus-first, 10-foot layouts. TV is not mobile with larger buttons.
- **Both**: use both references, define separate UI entry points, and audit each independently. Do **not** mix mobile and TV MaterialTheme/components within a TV design without careful need and compatibility checks.

## 5. Implement like a real app

- Preserve app architecture: unidirectional data flow, clear UI state, state hoisting, navigation contracts, lifecycle-awareness, previewability, stable item keys and sensible recomposition boundaries.
- Centralize semantic tokens (`BrandColors`, theme typography/shape/spacing tokens or existing equivalents). Use `MaterialTheme` semantics where possible. Do not scatter `Color(0x...)` and unrelated ad-hoc dp values throughout screens.
- Use genuine project assets. If imagery is missing, explain the intended asset slot and provide a readable fallback instead of claiming an image exists. Do not fetch copyrighted brand assets without permission.
- Build reusable components **only** for repeated product patterns; avoid a one-off abstraction forest.
- Include navigation, press/selection feedback, accessibility semantics, focus/keyboard behavior as appropriate, meaningful text alternatives, and localization-friendly strings.
- Check dark/light where supported by the product, dynamic type/font scaling, orientation and available width. Use real padding/insets rather than clipping content behind system bars.
- Keep diffs scoped to the request. Do not rewrite unrelated code, silently change app architecture or change application IDs.

## 6. Verify visually, then technically

When a build/emulator/screenshot path exists:

1. Compile the affected module. Run focused checks/tests available in the repo.
2. Capture mobile and/or TV screenshots or Compose previews, preferably with actual app content.
3. Compare the screenshot against the hierarchy and two distinctive signatures. Fix visual defects: cutoffs, illegible text, low contrast, unrelated containers, poor alignment, dead space, lost focus and accidental clipping.
4. Exercise primary, loading, empty, error and back-navigation paths. On TV, test with D-pad, not mouse alone.
5. Iterate at least once if the screenshot exposes problems. Do not claim screenshot verification when none was performed.

If device, build or image tooling is unavailable, perform a code-level review and **state explicitly what was not verified**.

## 7. Quality gate (must pass before claiming done)

Score each from 0 (weak) to 2 (strong):

| Criterion | What 2/2 means |
| --- | --- |
| Product specificity | Looks and reads like this particular product |
| Hierarchy | First action/content is instantly evident |
| Visual identity | Two deliberate, coherent signatures; not a default template |
| Platform fit | Touch/gestures or TV D-pad are naturally usable |
| Legibility | Contrast, type, truncation and scale work |
| Interaction | Feedback, state, back behavior and focus are correct |
| Real states | Loading, empty, error, success are credible |
| Engineering | Fits architecture, compiles if build is available |

Target >= 14/16. **Hard failures override the score:** inaccessible primary action; broken TV focus/Back; illegible important text; invented APIs/assets; false claims about testing.

## 8. Delivery format

After implementation, report briefly:
- **Direction**: one sentence and the two intentional visual decisions.
- **Changes**: files/components actually added or edited.
- **Verification**: build/tests/screenshots/D-pad tested, with honest results.
- **Limitations**: missing assets, tests or hardware, only if relevant.

Do not present a long design manifesto instead of shipping the requested code.

## Authoritative references

- Android Compose Material 3: https://developer.android.com/develop/ui/compose/designsystems/material3
- Compose for TV: https://developer.android.com/training/tv/playback/compose
- TV focus system: https://developer.android.com/design/ui/tv/guides/styles/focus-system
- TV layouts: https://developer.android.com/design/ui/tv/guides/styles/layouts
- Compose focus restoration: https://developer.android.com/develop/ui/compose/touch-input/focus/focus-restoration

When project versions and current documentation differ, investigate and state the compatibility constraint rather than guessing.

## Project Design System Compliance

Before proposing or implementing UI, read the repository's `AGENTS.md` and look for `docs/DESIGN_SYSTEM.md`. If approved, load and follow it as the visual source of truth; do not introduce conflicting components, colors, effects or patterns. If absent, initiate the visual identity creation process. If approval is pending, treat it as a proposal and do not assume its decisions are final. Keep product-specific tokens in that document, not here. Before completion check visual consistency, legibility, accessibility, interaction states, navigation and focus or touch behavior for the applicable Android and Android TV targets. Report what could not be verified.

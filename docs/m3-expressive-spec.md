# Material 3 Expressive: implementation spec (web first, Compose later)

Compiled 2026-11-24. Sources: the live m3.material.io pages (rendered in a browser, not just fetched), the AndroidX Compose Material3 token sources (`androidx-main`, `compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/tokens/*.kt`), material-color-utilities (MCU), material-web, and design.google.

**Legend.** **[m3]** means I read it on m3.material.io. **[cmp]** means I read it in the AndroidX Compose source, which is generated from the official design tokens. **[mcu]** means material-color-utilities. **[mw]** means material-web. **[calc]** means I derived it myself (for example, CSS curves computed from official spring constants). **unverified** means I could not confirm it from an official source.

Units: dp equals CSS px at 1x, and sp equals px with the user's font scaling (use `rem` on the web if you want to respect the user's font size).

---

## 0. Key facts up front
- M3 Expressive (May 2025) is an **evolution of M3, not "M4"**. M3 is not deprecated. It is based on 46 studies with more than 18,000 participants. **[m3 blog]**
- **14 new or updated components:** App bars, Button groups (new), Common buttons, Extended FAB, FAB menu (new), FABs, Icon buttons, Loading indicator (new), Navigation bar, Navigation rail, Progress indicators, Sliders, Split button (new), Toolbars (new). **[m3 blog]** Lists (Dec 2025) and Search (Feb 2025) received later expressive updates. **[m3]**
- **No longer recommended** **[m3]:**
  - Segmented button: use the connected button group instead.
  - Bottom app bar: use the docked toolbar.
  - Baseline navigation bar: use the flexible (short) navigation bar.
  - Baseline navigation rail: use the collapsed rail. The navigation drawer is replaced by the expanded rail.
  - Medium/large top app bars: use the medium flexible and large flexible app bars.
  - Small FAB.
  - Baseline extended FAB: use the small extended FAB.
  - Surface-colored FAB and extended FAB.
  - Speed dial and stacked small FABs: use the FAB menu.
  - Indeterminate circular progress for waits under 5 s: use the loading indicator.
- **Web status [m3]:**
  - "Web: Expressive" is **Unavailable** for components.
  - Motion on web is "compatible": use springs, or the official cubic-bezier conversions in §4.
  - material-web (`@material/web`) tokens are frozen at `v0_192` and are **pre-Expressive**. There are no spring tokens and no Expressive components in that package. **[mw]**
  - A vanilla web rebuild therefore has to hand-implement the Expressive components using the tokens in this document.

---

## 1. Color

### 1.1 Roles and tone mapping
Static baseline scheme from Compose tokens **[cmp]**. Tone numbers are the same as the MCU 2021 spec **[mcu]**, except where noted.

The baseline seed is #6750A4. The hex values below are the baseline scheme; dynamic schemes replace them.

| Role | Light tone | Light hex | Dark tone | Dark hex |
|---|---|---|---|---|
| primary | P40 | #6750A4 | P80 | #D0BCFF |
| on-primary | P100 | #FFFFFF | P20 | #381E72 |
| primary-container | P90 | #EADDFF | P30 | #4F378B |
| on-primary-container | P10 (baseline) / **P30 (dynamic, see note)** | #21005D | P90 | #EADDFF |
| inverse-primary | P80 | #D0BCFF | P40 | #6750A4 |
| secondary | S40 | #625B71 | S80 | #CCC2DC |
| on-secondary | S100 | #FFFFFF | S20 | #332D41 |
| secondary-container | S90 | #E8DEF8 | S30 | #4A4458 |
| on-secondary-container | S10 / **S30 dyn** | #1D192B | S90 | #E8DEF8 |
| tertiary | T40 | #7D5260 | T80 | #EFB8C8 |
| on-tertiary | T100 | #FFFFFF | T20 | #492532 |
| tertiary-container | T90 | #FFD8E4 | T30 | #633B48 |
| on-tertiary-container | T10 / **T30 dyn** | #31111D | T90 | #FFD8E4 |
| error | E40 | #B3261E | E80 | #F2B8B5 |
| on-error | E100 | #FFFFFF | E20 | #601410 |
| error-container | E90 | #F9DEDC | E30 | #8C1D18 |
| on-error-container | E10 / **E30 dyn** | #410E0B | E90 | #F9DEDC |
| surface / background | N98 | #FEF7FF | N6 | #141218 |
| on-surface / on-background | N10 | #1D1B20 | N90 | #E6E0E9 |
| surface-variant | NV90 | #E7E0EC | NV30 | #49454F |
| on-surface-variant | NV30 | #49454F | NV80 | #CAC4D0 |
| surface-dim | N87 | #DED8E1 | N6 | #141218 |
| surface-bright | N98 | #FEF7FF | N24 | #3B383E |
| surface-container-lowest | N100 | #FFFFFF | N4 | #0F0D13 |
| surface-container-low | N96 | #F7F2FA | N10 | #1D1B20 |
| surface-container | N94 | #F3EDF7 | N12 | #211F26 |
| surface-container-high | N92 | #ECE6F0 | N17 | #2B2930 |
| surface-container-highest | N90 | #E6E0E9 | N22 | #36343B |
| inverse-surface | N20 | #322F35 | N90 | #E6E0E9 |
| inverse-on-surface | N95 | #F5EFF7 | N20 | #322F35 |
| outline | NV50 | #79747E | NV60 | #938F99 |
| outline-variant | NV80 | #CAC4D0 | NV30 | #49454F |
| primary-fixed / -fixed-dim | P90 / P80 | #EADDFF / #D0BCFF | same | same |
| on-primary-fixed / -fixed-variant | P10 / P30 | #21005D / #4F378B | same | same |
| secondary-fixed / -dim | S90 / S80 | #E8DEF8 / #CCC2DC | same | same |
| on-secondary-fixed / -variant | S10 / S30 | #1D192B / #4A4458 | same | same |
| tertiary-fixed / -dim | T90 / T80 | #FFD8E4 / #EFB8C8 | same | same |
| on-tertiary-fixed / -variant | T10 / T30 | #31111D / #633B48 | same | same |
| scrim | N0 | #000000 | N0 | #000000 |
| shadow | N0 | #000000 | N0 | #000000 |
| surface-tint (deprecated) | = primary | | = primary | |

Notes on the table:
- **Dynamic on-*-container tones.** In Aug 2024 m3.material.io made the light-theme on-primary/secondary/tertiary/error-container roles "more colorful" **[m3]**. MCU 2021 dynamic schemes use **tone 30** for these in light **[mcu]**. The Compose static baseline still uses tone 10 **[cmp]**. For a fresh build, use 30.
- **Contrast levels.**
  - There are three levels: standard, medium and high (May 2025) **[m3]**.
  - MCU contrast curves for surface containers in light, per level: lowest 100; low 96→95; container 94→90; high 92→85; highest 90→80.
  - In dark: lowest 4→0; low 10→12; container 12→20; high 17→25; highest 22→30. Surface-bright in dark is 24→34. Surface-dim in light is 87→75. **[mcu 2021]**
- **Guaranteed contrast.**
  - Pairs give at least 3:1 **[m3]**.
  - Outline is for important boundaries and must reach 3:1 against surface.
  - Outline-variant is decorative only (dividers), or for borders of targets whose contents already reach 4.5:1 **[m3]**.
  - Chip strokes changed to outline-variant (Aug 2024) **[m3]**.
- **Fixed roles** keep the same tone in light and dark. Use them only where you don't need contrast to adapt **[m3]**.

### 1.2 Palette generation and scheme variants
- **HCT pipeline [m3]:**
  1. One source color produces 5 key colors: primary, secondary, tertiary, neutral and neutral variant (error is static).
  2. Each key color produces a tonal palette with tones 0–100: 0, 10, 20…100 plus 95, 98, 99, and more where needed.
  3. Tones are assigned to roles.
  - Tone gaps: 40 vs 100 gives about 3:1; 30 vs 98 gives about 7:1.
- **MCU 2025 color spec** (`color_spec_2025.ts`) **[mcu]** is the Expressive-era spec:
  - It adds roles **primary-dim, secondary-dim and tertiary-dim**.
  - It is platform-aware (phone vs watch).
  - It uses chroma multipliers per variant.
- **2025 phone tones:**
  - Light: surface 98 (97 for VIBRANT, 99 for yellow hues); container-low 96; container 94; high 92; highest 90.
  - Dark: surface **4**; low 6; container 9; high 12; highest 15.
  - Neutral surfaces get extra chroma: TONAL_SPOT ×1.25–1.7, EXPRESSIVE ×1.15–1.6, VIBRANT ×1.08–1.29.
  - Primary in TONAL_SPOT light is the max-chroma tone (`tMaxC`), not fixed 40.
- **Variants:** TONAL_SPOT (the Android default), NEUTRAL, VIBRANT, EXPRESSIVE, FIDELITY, CONTENT, MONOCHROME, RAINBOW, FRUIT_SALAD.
- **Recommendation for the web:**
  - Generate the scheme with `@material/material-color-utilities` (npm) or Material Theme Builder, using the 2025 spec and TONAL_SPOT, or VIBRANT/EXPRESSIVE for a bolder look.
  - Emit CSS custom properties `--md-sys-color-*` for light, dark and the medium/high contrast levels.
  - Exact 2025-spec tones vary per seed color and are computed by contrast curves, so don't hand-code them.

### 1.3 Expressive color guidance [m3 blog/roles]
- **"Apply rich and nuanced colors":** mix primary, secondary and tertiary, and use contrast between them to prioritize actions. Create hierarchy with surface tones. Without contrast, elements blend together.
- **Role semantics:**
  - Primary is for the most important actions (FAB, high-emphasis buttons, active states).
  - Secondary is for less prominent elements (nav active indicator, tonal buttons, filter chips).
  - Tertiary is for small, specially emphasized elements (badges, input fields).
- **Surface layout:**
  - The common pairing is a surface body with surface-container navigation. Keep these mappings consistent across breakpoints.
  - "Contain content for emphasis": give the most important content "the brightest surface mapping".
- **Default component surfaces [cmp]:**

  | Surface role | Components |
  |---|---|
  | surface-container | Nav bar, docked toolbar, standard floating toolbar, app bar on scroll |
  | surface-container-high | Dialog, search bar |
  | surface-container-highest | Filled card |
  | surface-container-low | Bottom sheet |
  | surface | App bar at rest, rail, list |

- **Vibrant toolbar:** container is primary-container, with on-primary-container icons **[cmp]**.

---

## 2. Typography

### 2.1 Type scale
All values from Compose `TypeScaleTokens` **[cmp]**. m3.material.io says there are 30 styles: 15 baseline and 15 emphasized **[m3]**.

| Style | Font | Size/Line (sp) | Tracking (sp) | Weight | Emphasized weight |
|---|---|---|---|---|---|
| display-large | brand | 57/64 | -0.25 | 400 | 500 |
| display-medium | brand | 45/52 | 0 | 400 | 500 |
| display-small | brand | 36/44 | 0 | 400 | 500 |
| headline-large | brand | 32/40 | 0 | 400 | 500 |
| headline-medium | brand | 28/36 | 0 | 400 | 500 |
| headline-small | brand | 24/32 | 0 | 400 | 500 |
| title-large | brand | 22/28 | 0 | 400 | 500 |
| title-medium | plain | 16/24 | 0.15 | 500 | **700** |
| title-small | plain | 14/20 | 0.1 | 500 | **700** |
| body-large | plain | 16/24 | 0.5 | 400 | 500 |
| body-medium | plain | 14/20 | 0.25 | 400 | 500 |
| body-small | plain | 12/16 | 0.4 | 400 | 500 |
| label-large | plain | 14/20 | 0.1 | 500 | **700** |
| label-medium | plain | 12/16 | 0.5 | 500 | **700** |
| label-small | plain | 11/16 | 0.5 | 500 | **700** |

- **Emphasized styles differ only in weight.** Size, line height and tracking are identical. The rule is: Regular becomes Medium, and Medium becomes Bold.
- **Compose:** emphasized styles are `MaterialTheme.typography.displayLargeEmphasized` … `labelSmallEmphasized`, available under `MaterialExpressiveTheme`.
- **Web conversion:** tracking in sp ÷ size gives em. For example, body-large 0.5/16 = 0.03125em.
- **Aug 2026:** the type scale can adapt line height to the script's height category (small, medium, large, extra large) **[m3]**.

### 2.2 Fonts [m3 fonts, typography overview]
- **Default.** Roboto is the static font applied by default to all M3 components. Compose's token typefaces "Brand" and "Plain" both resolve to `FontFamily.SansSerif` (Roboto on Android) **[cmp]**.
- **Roboto Flex** (variable) "can be used on its own… but is not yet part of the M3 typescale" **[m3]**.
  - Axes: slnt, wdth, wght, GRAD, opsz.
  - Advanced axes: XOPQ, YOPQ, XTRA, YTUC, YTLC, YTAS, YTDE, YTFI.
  - Fallback order: Roboto Flex → Roboto → Noto Sans.
- **Google Sans Flex** is featured on m3.material.io: "six variable axes", with shapes that share its roundness. It is open source.
  - Exact axis list: **unverified**. It likely includes wght, wdth, opsz, GRAD, slnt and a ROND roundness axis, but I could not confirm this.
  - Licensing and availability through Google Fonts: **unverified**. Check Google Fonts before use.
- **Web recommendation:**
  - Use Roboto Flex from Google Fonts with `font-optical-sizing:auto`.
  - Emphasized styles: raise `wght` (500 → 700).
  - Optional: GRAD for dark mode.
  - Mono option: Roboto Mono, with `font-variant-numeric: tabular-nums` for timetable times.
- **Expressive tactic:** "Guide attention with typography". Use emphasized styles for headlines and key actions or information (for example, "begin recording" or unread counts), and create editorial moments with heavier weights and larger sizes **[m3 blog]**.

### 2.3 Component type assignments [cmp]

| Component | Type style |
|---|---|
| Buttons by height | <56 → label-large; 56–95 → title-medium; 96–135 → headline-small; ≥136 → headline-large (`ButtonDefaults.textStyleFor`) |
| Extended FAB S / M / L | title-medium / title-large / headline-small |
| App bar small | title-large, subtitle label-medium |
| Medium flexible app bar | headline-medium, subtitle label-large |
| Large flexible app bar | display-small, subtitle title-medium |
| Nav bar label | label-medium |
| Nav rail item label | label-medium |
| Primary tab | title-small |
| Dialog | headline-small; body-medium supporting text; label-large actions |
| Snackbar | body-medium; label-large action |
| Lists | body-large label; body-medium supporting text; label-small overline and trailing text |
| Search | body-large |
| Slider value label | label-large |

---

## 3. Shape

### 3.1 Corner radius scale
Confirmed in Compose `ShapeTokens` **[cmp]** and **[m3]**.

| Token | dp |
|---|---|
| none | 0 |
| extra-small | 4 |
| small | 8 |
| medium | 12 |
| large | 16 |
| **large-increased** (new) | 20 |
| extra-large | 28 |
| **extra-large-increased** (new) | 32 |
| **extra-extra-large** (new) | 48 |
| full | fully round (`CircleShape`; on the web use `9999px` or `50%`) |

- Partial variants: extra-small-top (4,4,0,0), large-top, large-start, large-end and extra-large-top (28,28,0,0, used by bottom sheets).
- Expressive change: "full" is now its own token, where it was previously 50% of component size **[m3]**.
- Compose names: `ShapeDefaults.ExtraSmall/Small/Medium/Large/LargeIncreased/ExtraLarge/ExtraLargeIncreased/ExtraExtraLarge`, plus `CircleShape`.

### 3.2 Shape library
There are 35 shapes. Names are from Compose `MaterialShapes` **[cmp]**; m3.material.io also says "35 shapes" **[m3]**.

Circle, Square, Slanted, Arch, Fan, Arrow, SemiCircle, Oval, Pill, Triangle, Diamond, ClamShell, Pentagon, Gem, VerySunny, Sunny, Cookie4Sided, Cookie6Sided, Cookie7Sided, Cookie9Sided, Cookie12Sided, Ghostish, Clover4Leaf, Clover8Leaf, Burst, SoftBurst, Boom, SoftBoom, Flower, Puffy, PuffyDiamond, PixelCircle, PixelTriangle, Bun, Heart.

- **Compose API:**
  - `MaterialShapes.X` is a `RoundedPolygon` from `androidx.graphics.shapes`.
  - Use `.toShape()` to clip.
  - Use `Morph(a, b)` with progress for morphing.
- **Web:**
  - No official SVG set is published in code. Export the shapes from the Figma Design Kit, then morph with SVG path interpolation (for example, flubber), or with CSS `clip-path: path()` where the point counts match.
  - The exact geometry is in `MaterialShapes.kt`: vertex counts and rounding radii, reproducible with the `@material/shapes`-style RoundedPolygon logic. A JS port of `androidx.graphics.shapes`: **unverified**.
- **Guidance [m3]:**
  - Shape morph should respond to interaction: tap, swipe, scroll, release, long-press.
  - Use morph to convey state (selected), progress (loading, typing) and environment changes.
  - "Embrace tension": mix round and square.
  - Shape is versatile, not semantic: don't give one shape a fixed meaning.
  - **Use abstract shapes sparingly** in functional UI. They suit decorative elements, image crops and avatars best.
  - Caution: "smaller shapes can result in essential actions looking less important".
  - "Break from the surrounding shape style to draw attention."

### 3.3 Morph behaviors to implement (from component tokens) [cmp]
- **Buttons:** rest shape round (full) or square. On press, morph to a smaller radius (the pressed token). Toggle buttons morph round → square when selected.
- **Icon buttons:** the selected round shape becomes a square-ish radius; the selected square shape becomes full.
- **Connected button group:**
  - Inner corners are 8dp; pressed inner corners are 4dp.
  - The selected item becomes 50% (fully round), outer corners stay full, and the gap is 2dp.
- **Split button:** inner corners 4dp, becoming 12dp on hover or press. The trailing button becomes fully round (50%) when selected (menu open), and its chevron rotates 180°.
- **Lists (expressive):** item radius 4dp at rest. Hover is 12dp. Pressed, focused, selected and dragged are 16dp.

---

## 4. Motion

### 4.1 Spring tokens [cmp `ExpressiveMotionTokens`/`StandardMotionTokens`, m3]
Mass = 1. Compose: `MaterialTheme.motionScheme.defaultSpatialSpec()`, `fastEffectsSpec()` and so on. `MaterialExpressiveTheme` defaults to `MotionScheme.expressive()`.

| Token | Expressive: damping / stiffness | Standard: damping / stiffness |
|---|---|---|
| spring.fast.spatial | **0.6 / 800** | 0.9 / 1400 |
| spring.default.spatial | **0.8 / 380** | 0.9 / 700 |
| spring.slow.spatial | **0.8 / 200** | 0.9 / 300 |
| spring.fast.effects | 1.0 / 3800 | 1.0 / 3800 |
| spring.default.effects | 1.0 / 1600 | 1.0 / 1600 |
| spring.slow.effects | 1.0 / 800 | 1.0 / 800 |

- **Spatial springs** are for position, size, rotation and corner radius. They overshoot.
- **Effects springs** are for color and opacity. They never overshoot.
- **Speed:**
  - Fast is for small components (buttons, switches).
  - Default is for partial-screen motion (bottom sheet, rail expand).
  - Slow is for full-screen motion.
  - "All component motion is driven by two tokens: expressive fast spatial and expressive fast effects." **[m3]**
- **When to use which scheme:** Expressive is the default and is used for hero and key moments. Standard is for utilitarian products. You can swap schemes per element **[m3]**.

### 4.2 Official web conversion [m3 motion/specs]
Use these cubic-bezier curves for animations without interruptions or gestures.

| Spring | cubic-bezier | Duration |
|---|---|---|
| Expressive fast spatial | (0.42, 1.67, 0.21, 0.90) | 350ms |
| Expressive default spatial | (0.38, 1.21, 0.22, 1.00) | 500ms |
| Expressive slow spatial | (0.39, 1.29, 0.35, 0.98) | 650ms |
| Expressive fast effects | (0.31, 0.94, 0.34, 1.00) | 150ms |
| Expressive default effects | (0.34, 0.80, 0.34, 1.00) | 200ms |
| Expressive slow effects | (0.34, 0.88, 0.34, 1.00) | 300ms |
| Standard fast spatial | (0.27, 1.06, 0.18, 1.00) | 350ms |
| Standard default spatial | (0.27, 1.06, 0.18, 1.00) | 500ms |
| Standard slow spatial | (0.27, 1.06, 0.18, 1.00) | 750ms |
| Standard fast / default / slow effects | same as expressive effects | 150 / 200 / 300ms |

### 4.3 More faithful CSS `linear()` curves [calc]
I sampled the analytic damped-spring step response at 21 points over the official durations. Use these where `linear()` is supported (Chromium 113+, Firefox 112+, Safari 17.2+), with the §4.2 cubic-beziers as fallback.

```css
:root{
 --md-spring-expr-fast-spatial: linear(0,.099,.317,.561,.775,.934,1.034,1.083,1.095,1.084,1.063,1.04,1.02,1.005,.996,.992,.991,.992,.994,.996,1); /*350ms*/
 --md-spring-expr-default-spatial: linear(0,.091,.28,.483,.66,.796,.891,.953,.988,1.007,1.014,1.015,1.013,1.01,1.007,1.005,1.003,1.001,1.001,1,1); /*500ms*/
 --md-spring-expr-slow-spatial: linear(0,.082,.257,.45,.623,.761,.863,.932,.975,.999,1.011,1.015,1.015,1.012,1.009,1.007,1.004,1.003,1.001,1.001,1); /*650ms*/
 --md-spring-std-spatial: linear(0,.145,.398,.622,.783,.885,.945,.976,.992,.999,1.001,1.002,1.001,1.001,1.001,1,1,1,1,1,1); /*fast 350/def 500/slow 750 – overshoot ~0.2%*/
 --md-spring-fast-effects: linear(0,.079,.237,.404,.552,.672,.764,.833,.884,.92,.945,.962,.974,.983,.988,.992,.995,.997,.998,.999,1); /*150ms*/
 --md-spring-default-effects: linear(0,.062,.191,.337,.475,.594,.692,.769,.829,.874,.908,.934,.952,.966,.976,.983,.988,.991,.994,.996,1); /*200ms*/
 --md-spring-slow-effects: linear(0,.068,.209,.364,.506,.626,.722,.796,.852,.894,.925,.947,.963,.974,.982,.987,.991,.994,.996,.997,1); /*300ms*/
}
```

- Peak overshoot: expressive fast spatial about 9.5%; expressive default and slow spatial about 1.5%.
- **Interruptible or gesture-driven motion** (drag-to-dismiss sheets, pull-to-refresh) needs a JS spring with the same constants. A spring integrator is about 20 lines, or use Web Animations with recomputed keyframes. Mass is 1, ω = √k, and damping ratio ζ as in the token table.
- **Reduced motion:** honor `prefers-reduced-motion`. Swap spatial springs for effects (fade) or for no motion. The m3 spec page did not say this; it is standard accessibility practice.

### 4.4 Legacy easing and duration tokens [cmp `MotionTokens`; m3 says "still available as fallback, currently used for transitions"]

| Token | Value |
|---|---|
| emphasized | cubic-bezier(0.2, 0, 0, 1). The true "emphasized" curve is a two-segment path; this is the bezier approximation. |
| emphasized-decelerate | cubic-bezier(0.05, 0.7, 0.1, 1) |
| emphasized-accelerate | cubic-bezier(0.3, 0, 0.8, 0.15) |
| standard | cubic-bezier(0.2, 0, 0, 1) |
| standard-decelerate | cubic-bezier(0, 0, 0, 1) |
| standard-accelerate | cubic-bezier(0.3, 0, 1, 1) |
| legacy | cubic-bezier(0.4, 0, 0.2, 1) |
| legacy-decelerate / -accelerate | cubic-bezier(0, 0, 0.2, 1) / cubic-bezier(0.4, 0, 1, 1) |
| linear | cubic-bezier(0, 0, 1, 1) |
| short1–4 | 50 / 100 / 150 / 200 ms |
| medium1–4 | 250 / 300 / 350 / 400 ms |
| long1–4 | 450 / 500 / 550 / 600 ms |
| extra-long1–4 | 700 / 800 / 900 / 1000 ms |

Typical legacy pairings (from M3 transition guidance; **unverified** in this session):
- Enter: emphasized-decelerate, 400–500ms.
- Exit: emphasized-accelerate, 200ms.
- Small utility motion: standard, 200–300ms.

---

## 5. Elevation [m3 elevation/tokens, cmp `ElevationTokens`]

| Level | dp | Resting-level components [m3] |
|---|---|---|
| 0 | 0 | App bar (not scrolled), filled/tonal/outlined buttons, button groups, filled/outlined cards, carousel, chips, full-screen dialog, FAB/ext FAB inside a rail, FAB-menu list items, icon buttons, list, nav rail, segmented button, docked side sheet, slider, split button, tabs |
| 1 | 1 | Banner, modal bottom sheet, elevated button, elevated card, elevated chips, modal nav drawer, modal side sheet |
| 2 | 3 | App bar (scrolled), menu, navigation bar, rich tooltip, toolbar, modal expanded rail [cmp] |
| 3 | 6 | Date/time pickers, modal dialog, FAB, extended FAB, FAB-menu close button, search |
| 4 | 8 | (not a resting level; used for dragged list items and chips, and elevated card dragged) |
| 5 | 12 | (not a resting level) |

- Hover or focus usually raises elevation by one level **[m3]**.
- Surface tint is **deprecated**. Use surface-container roles for tonal separation, and use elevation only for shadow and z-order **[m3]**.
- The M3 app bar uses no shadow when scrolled; instead its color fill switches from surface to surface-container **[m3, cmp]**.
- **CSS shadows** (material-web). Each level uses two layers in `--md-sys-color-shadow`: a key layer at opacity 0.3 and an ambient layer at opacity 0.15 **[mw]**.

  | Level | Key layer (0.3) | Ambient layer (0.15) |
  |---|---|---|
  | 1 | `0 1px 2px 0` | `0 1px 3px 1px` |
  | 2 | `0 1px 2px 0` | `0 2px 6px 2px` |
  | 3 | `0 1px 3px 0` | `0 4px 8px 3px` |
  | 4 | `0 2px 3px 0` | `0 6px 10px 4px` |
  | 5 | `0 4px 4px 0` | `0 8px 12px 6px` |

---

## 6. Components

### 6.0 State layers [m3 states; cmp `StateTokens`] — confirmed
- Hover **8%**, focus **10%**, pressed **10%**, dragged **16%**.
- The layer color is the content ("on-") color of the container.
- Disabled (M3 convention, **unverified** in this session): container on-surface at 12%, content on-surface at 38%.
- Scrim opacity is **32%** **[cmp `ScrimTokens`]**.

### 6.1 Buttons [cmp Button*Tokens, m3]
- **Color styles** are now *configurations*: elevated, filled, tonal, outlined, text.
- **Variants:** default and toggle (selection).
- **Shapes:** round or square. The shape morphs on press, and toggle buttons morph when selected.

| Size | Height | Square radius | Pressed radius | Selected (toggle) radius | Icon | Side padding | Icon–label gap | Outline | Label style |
|---|---|---|---|---|---|---|---|---|---|
| XS | 32 | 12 | 8 | 12 | 20 | 12 (cmp ToggleButton XS) / 16 token | 8 | 1 | label-large |
| S (default) | 40 | 12 | 8 | 12 | 20 | **16** (24 no longer recommended) | 8 | 1 | label-large |
| M | 56 | 16 | 12 | 16 | 24 | 24 | 8 | 1 | title-medium |
| L | 96 | 28 | 16 | 28 | 32 | 48 | 12 | 2 | headline-small |
| XL | 136 | 28 | 16 | 28 | 40 | 64 | 16 | 3 | headline-large |

- Round shape is fully rounded. A round toggle morphs round → square when selected (`ToggleButtonDefaults.checkedShape` = the square token).
- **Colors:**
  - Filled: primary / on-primary. Selected toggle: unselected filled is surface-container with on-surface-variant (M3 toggle convention, **unverified** in this session).
  - Tonal: secondary-container / on-secondary-container.
  - Outlined: outline-variant border.
  - Text: primary.
- **Motion:** shape morph uses the fast-spatial spring; color uses fast-effects.
- **Touch target:** at least 48dp even for XS (32dp). Extend the hit area invisibly.

### 6.2 Icon buttons [cmp *IconButtonTokens, m3]
Configurations: filled, tonal, outlined, standard. Five sizes, round or square, and **three widths: narrow, default and wide**.

| Size | Height | Icon | Side padding narrow / default / wide → width | Square radius | Pressed | Selected round → | Selected square → |
|---|---|---|---|---|---|---|---|
| XS | 32 | 20 | 4 / 6 / 10 → 28 / 32 / 40 | 12 | 8 | 12 | full |
| S | 40 | 24 | 4 / 8 / 14 → 32 / 40 / 52 | 12 | 8 | 12 | full |
| M | 56 | 24 | 12 / 16 / 24 → 48 / 56 / 72 | 16 | 12 | 16 | full |
| L | 96 | 32 | 16 / 32 / 48 → 64 / 96 / 128 | 28 | 16 | 28 | full |
| XL | 136 | 40 | 32 / 48 / 72 → 104 / 136 / 184 | 28 | 16 | 28 | full |

- Widths are computed as icon + 2 × padding.
- Outline width: 1 for XS–M, 2 for L, 3 for XL.

### 6.3 Button groups [m3, cmp]
- **Standard group:**
  - Gap: **12dp** (S tokens).
  - When pressed, the button **expands its width by 15%** (`ButtonGroupDefaults.ExpandedRatio = 0.15f`) and neighbors compress. Use the fast spatial spring.
  - Works with all sizes XS–XL. Applies one default shape (round or square) to all buttons.
- **Connected group** (replaces the segmented button):
  - Gap **2dp**, height 40 (S).
  - Outer corners are full. Inner corners are **8dp** (small); pressed inner corners are 4dp.
  - **Selected item becomes fully round (50%)**.
  - Use it for single-select or multi-select toggles. Good for transit modes, line filters or direction.

### 6.4 Split button [cmp SplitButton*Tokens, m3]
Sizes XS–XL. Styles: elevated, filled, tonal, outlined.

| Size | Height | Gap | Leading button padding (L/R) | Trailing button padding (L/R) | Chevron |
|---|---|---|---|---|---|
| S | 40 | 2 | 16 / 12 | 13 / 13 | 22 |
| M | 56 | 2 | 24 / 24 | 15 / 15 | 26 |

- Outer corners are full. Inner corners are 4dp, becoming 12dp on hover or press.
- When the menu is open, the trailing button becomes fully round and the chevron rotates.

### 6.5 FAB, extended FAB, FAB menu [cmp, m3]

**FAB**

| Size | Size (dp) | Radius | Icon |
|---|---|---|---|
| FAB (baseline) | 56 | 16 | 24 |
| **Medium (new)** | 80 | 20 (large-increased) | 28 |
| Large | 96 | 28 | 32 |
| Small | 40 | 12 | 24 (no longer recommended) |

- Elevation level 3.
- Colors:
  - Tone styles: primary, secondary, tertiary (on-X content).
  - Container styles: primary-, secondary- or tertiary-container. These were renamed from primary/secondary/tertiary.
  - Surface FAB is no longer recommended.

**Extended FAB**

| Size | Height | Radius | Icon | Side padding | Icon–label gap | Label |
|---|---|---|---|---|---|---|
| Small | 56 | 16 | 24 | 16 | 8 | title-medium |
| Medium | 80 | 20 | 28 | 26 | 16 | title-large |
| Large | 96 | 28 | 32 | 28 | 20 | headline-small |

**FAB menu (new)**
- Opens from any FAB. The FAB morphs into a 56dp fully round **close button** with a 20dp icon, elevation 3, and contrasting color.
- **Items:**
  - 56dp high, fully round pill, elevation 3.
  - 24dp icon, 8dp icon–label gap, 24dp side padding.
  - 4dp between items; 8dp between the close button and the list.
- Items use a contrasting color: for example, a primary-container FAB gets primary items, or vice versa (**unverified** exact mapping).
- 2–6 items is the usual guidance (**unverified**).

### 6.6 Toolbars (replace bottom app bar) [cmp, m3]
- **All toolbars:** 64dp high, center-aligned, equal spacing between items, minimum outer padding 16dp **[m3]**.
- **Docked toolbar:**
  - Full width, square corners (0), surface-container.
  - 16dp leading and trailing padding; spacing between items 4–32dp.
  - Can embed the primary action instead of a FAB.
- **Floating toolbar:**
  - Horizontal or vertical. Fully round pill, 64dp.
  - 8dp inner leading and trailing padding, 4dp between items, **16dp from the screen edge**.
  - Pairs with a FAB at an 8dp gap.
  - Hides on scroll after 40dp of scroll distance (Compose `ScrollDistanceThreshold`).
  - Elevation level 2 per the elevation table; may render with a shadow (exact level for floating: **unverified**).
- **Colors:**
  - **Standard:** surface-container.
  - **Vibrant:** primary-container, with on-primary-container icons. A selected button in vibrant has a surface-container background with on-surface icons.

### 6.7 Navigation bar: flexible / "short" [cmp NavigationBar*Tokens, m3]
- Container **64dp** (the old baseline was 80dp), surface-container. Tokens list level 2; the m3 overview says "no shadow".
- 3–5 destinations, for compact and medium windows.
- **Vertical item (compact):**
  - Active indicator pill 56×32, fully round, secondary-container.
  - 24dp icon, 4dp icon–label gap, label-medium.
- **Horizontal item (medium windows):**
  - Indicator 40dp high, 16dp leading and trailing padding, icon and label side by side.
- **Colors:**
  - Active icon: on-secondary-container.
  - Active label: **secondary** (changed from on-surface-variant).
  - Inactive: on-surface-variant.
- Compose: `ShortNavigationBar` with `ShortNavigationBarItem` (`ShortNavigationBarArrangement.EqualWeight/Centered`).

### 6.8 Navigation rail: collapsed and expanded [cmp, m3]
- **Collapsed:**
  - Width **96dp** (narrow variant 80), surface, elevation 0. Top space 44dp; 4dp vertical gap between items.
  - Item: indicator 56×32, label-medium.
- **Expanded:**
  - Width 220–360dp. Modal or non-modal; modal is level 2 with 16dp radius.
  - It can collapse into the collapsed rail or hide. It replaces the navigation drawer.
- Active label on vertical items is secondary.
- Compose: `WideNavigationRail`, `ModalWideNavigationRail`, `WideNavigationRailItem`.

### 6.9 App bars [cmp AppBar*Tokens, m3]
- **Renamed** from "top app bar" to "app bar".
- **Shared:**
  - Padding 4dp leading and trailing, 24dp icons, 32dp avatar.
  - Container is surface; on scroll it becomes surface-container (no shadow).

| Variant | Height | Title | Subtitle |
|---|---|---|---|
| Small | 64 | title-large | label-medium |
| **Medium flexible** | 112 (136 with two-line title/subtitle) | headline-medium | label-large |
| **Large flexible** | 120 (152 with subtitle or wrapping) | display-small | title-medium |
| **Search app bar** | Search bar inside, with icons inside or outside; opens the search view | | |

- New features: subtitle, left or center alignment, title wrapping, and slots for imagery and filled buttons.
- Compose: `TopAppBar(subtitle=…)`, `MediumFlexibleTopAppBar`, `LargeFlexibleTopAppBar`, `AppBarWithSearch` / `SearchBar`.

### 6.10 Loading indicator (new) [cmp LoadingIndicator, m3]
- **Use:** progress under 5 s, pull-to-refresh, replacing indeterminate circular progress. It is never decorative. Don't use it for processes that switch from indeterminate to determinate.
- **Size:**
  - Container 48×48, fully round.
  - Active shape 38dp.
  - It can scale.
- **Colors:**
  - Uncontained: active indicator is primary.
  - Contained: primary-container background with on-primary-container shape.
- **Morph sequence (indeterminate):** SoftBurst → Cookie9Sided → Pentagon → Pill → Sunny → Cookie4Sided → Oval → loop.
- **Morph timing:**
  - A new morph every **650ms**, driven by spring(ζ 0.6, k 200).
  - Each morph adds +90° rotation.
  - A global linear rotation runs underneath: **360° per 4666ms**.
- Compose: `LoadingIndicator()`, `ContainedLoadingIndicator()`.

### 6.11 Progress indicators: wavy (Expressive since Aug 2024) [cmp]
- **Linear:**
  - Track and active thickness 4dp (track height is configurable).
  - Wavy: amplitude 3dp, wavelength 40dp (indeterminate 20dp), total wave height 10dp.
  - 4dp gap between the active part and the track.
  - 4dp stop dot at the end.
- **Circular:**
  - Size 40dp (wavy 48dp), thickness 4dp.
  - Wave amplitude 1.6dp, wavelength 15dp, 4dp gap.
- Colors: primary active, secondary-container track (M3 convention). Wavy and flat versions are both valid.
- Compose: `LinearWavyProgressIndicator`, `CircularWavyProgressIndicator`.

### 6.12 Sliders [m3 specs, cmp SliderTokens]
- Configurations: standard (formerly continuous), centered, range, stops (formerly discrete), horizontal or vertical, inset icon.
- Handle is 4dp wide, narrowing to 2dp when pressed. Value label container is 44×48, inverse-surface.
- Stop dots are 4dp, secondary-container.
- Colors: active track primary; inactive track secondary-container.

| Size | Track height | Handle height | Track radius | Inset icon |
|---|---|---|---|---|
| XS (default) | 16 | 44 | 8 | – |
| S | 24 | 44 | 8 | – |
| M | 40 | 52 | 12 | 24 |
| L | 56 | 68 | 16 | 24 |
| XL | 96 | 108 | 28 | 32 |

### 6.13 Switch [cmp SwitchTokens]
- Track 52×32, fully round, 2dp outline when unselected.
- **Handle:**
  - Unselected 16dp; with an icon 24dp.
  - Selected 24dp.
  - Pressed 28dp.
  - Icons 16dp. State layer 40dp.
- **Colors:**
  - Selected: track primary, handle on-primary, icon on-primary-container.
  - Unselected: track surface-container-highest, outline and handle outline.
- Motion: handle size and position use the expressive fast spatial spring; colors use fast effects.

### 6.14 Lists: expressive and segmented (Dec 2025) [m3, cmp ListTokens]
- **Heights:** 1-line 56, 2-line 72, 3-line 88. Elements are top-aligned when the item is 88dp or taller, or has three or more lines.
- **Padding:** 16dp leading and trailing, 10dp top and bottom, 12dp between elements.
- **Content sizes:**
  - Expressive leading and trailing icons: 20dp (baseline 24).
  - Avatar 40dp (primary-container, title-medium).
  - Image 56dp with 8dp radius.
  - Video 100×56 or 114×64.
- **Segmented style:**
  - Items are separate tiles with a **2dp gap**.
  - The group container has a 16dp radius (large); each item is 4dp at rest.
  - Item radius: hover 12; pressed, focused and selected 16.
  - Item container is surface. This is a good fit for timetable rows grouped by line.
- **Selected state:** secondary-container with on-secondary-container content.
- **Dragged state:** elevation 4, 16dp radius.
- Segmented menus use similar rules: first and last children 12dp outer and 4dp inner; 2dp gap; items 44dp high.

### 6.15 Cards, chips, bottom sheets, dialogs, snackbar, tabs, search, carousel
These had no Expressive changes listed except search; values are current M3 **[cmp]**.

**Cards**
- Filled: surface-container-highest, 12dp radius, level 0 (hover 1).
- Elevated: level 1 (hover 2, dragged 4), 12dp.
- Outlined: outline-variant border, 12dp.

**Chips**
- 32dp high, 8dp radius (Assist).
- 18dp icons, label-large.
- Unselected stroke is 1dp outline-variant. Dragged elevation is 4.
- A newer generic `ChipsTokens` set suggests 12dp unselected, full when selected and 8dp pressed: an **unverified** Expressive direction, not in published guidance.

**Bottom sheet**
- Top corners 28dp, surface-container-low, level 1.
- Drag handle 32×4, on-surface-variant.
- Scrim 32%.

**Dialog**
- 28dp radius, surface-container-high, level 3.
- Headline-small headline, secondary icon, primary action text.

**Snackbar**
- Inverse-surface, 4dp radius, level 3.
- 48dp single-line, 68dp two-line.
- Inverse-primary action.

**Tabs (primary)**
- 48dp high (64dp with icon and label), title-small.
- Active indicator 3dp with a 3dp radius.

**Search** (Feb 2025)
- Bar 56dp, fully round, surface-container-high, body-large text, level 3 per tokens.
- Two styles: contained (recommended) and divided. Gaps can split results into groups.
- **The bar widens when focused.**
- Search view: docked has 28dp radius; full-screen has 0 radius and a 72dp header.

**Carousel**
- Layouts: multi-browse, uncontained, uncontained multi-aspect, hero, center-aligned hero, full-screen.
- Small items are 40–56dp wide.
- Item corner radius is commonly extra-large (28dp) (**unverified** default).
- Parallax on item visuals.

### 6.16 Compose Expressive entry points (for the later port)
- Theme: `MaterialExpressiveTheme(colorScheme, motionScheme = MotionScheme.expressive(), shapes, typography)`.
- Many components need `@OptIn(ExperimentalMaterial3ExpressiveApi::class)`.
- **APIs:** `ButtonGroup`, `ToggleButton`, `SplitButtonLayout`, `FloatingActionButtonMenu`, `MediumFloatingActionButton`, `HorizontalFloatingToolbar`, `FlexibleBottomAppBar` (docked toolbar), `ShortNavigationBar`, `WideNavigationRail`, `LoadingIndicator`, `LinearWavyProgressIndicator`, `MediumFlexibleTopAppBar`, `MaterialShapes`.
- On m3.material.io, 21 Compose components use the physics motion system by default.

---

## 7. Expressive tactics and layout

### 7.1 Tactics [m3 blog, design.google]
1. **Use a variety of shapes.** Mix classic and abstract shapes and round and square ones; break the surrounding shape style to draw focus.
2. **Rich, nuanced color.** Use contrast between primary, secondary and tertiary plus surface tones.
3. **Guide attention with typography.** Emphasized styles, larger sizes and heavier weights create editorial moments.
4. **Contain content for emphasis.** Group content logically; give key content ample space and the brightest surface.
5. **Fluid, natural motion.** Springs, shape morph and surface effects.
6. **Leverage component flexibility.** Adapt controls to context and screen size.
7. **Combine tactics into hero moments.** Use **one or two per product**; more overwhelms. Ask two questions: is the interaction emotionally impactful, and is it a key interaction?

**Research results** (design.google):
- Key UI elements were spotted up to 4× faster.
- Up to 87% of 18–24-year-olds preferred the expressive designs.
- Users aged 45+ matched younger users at spotting elements.

**Avoiding overuse** (design.google):
- Respect established patterns. Finance-like contexts are an example, and a scattered playlist layout lost on usability.
- Never drop labels for style. Removing email action labels hurt usability.
- A strong minority prefers calmer designs.
- Meet accessibility standards.
- Test.

For a transit PWA, candidate hero moments: the "next departure" countdown and the card-balance or tap moment. Keep the timetable list utilitarian, for example by using the segmented list.

### 7.2 Layout [m3 breakpoints; developer.android.com]

| Breakpoint | Width (dp) | Panes | Navigation |
|---|---|---|---|
| Compact | <600 | 1 | Nav bar; modal expanded rail |
| Medium | 600–839 | 1 (recommended) or 2 if low density | Nav bar (horizontal items) or modal expanded rail |
| Expanded | 840–1199 | 2 recommended | Standard or modal expanded rail (collapsed rail common) |
| Large | 1200–1599 | 2 recommended | Expanded rail |
| Extra-large | ≥1600 | 1–3 | Expanded rail |

- **Height classes:** compact <480, medium 480–899, expanded ≥900.
- **Margins:**
  - Compact: **16dp** **[m3]**.
  - Medium and larger: 24dp (**unverified** this session; m3 convention).
  - Spacer between panes: 24dp (**unverified**).
- **Grid:** 4dp baseline; 8dp for most component spacing (**unverified** wording; all component tokens above are multiples of 2 or 4).
- **Communication and action components by breakpoint:**
  - Compact: simple or full-screen dialog; bottom sheet for actions.
  - Medium and above: menu for actions.

---

## 8. Accessibility [m3 designing/structure, color roles]
- **Touch targets:** at least **48×48dp** (about 9mm). Pointer targets at least 44×44dp. A 24dp icon sits inside a 48dp target.
  - XS buttons (32) and narrow icon buttons need invisible hit-area padding.
- **Contrast:**
  - Role pairs are built for at least 3:1.
  - Body text must reach 4.5:1 (WCAG AA).
  - Outline must reach 3:1 against surface.
  - outline-variant is decorative only.
  - Offer medium and high contrast schemes; MCU provides them through the contrast level.
- **Labels:** keep labels and clear function; don't trade clarity for expression.
- **Motion:** honor `prefers-reduced-motion`. The loading indicator respects an infinite-animation policy in Compose.
- **Type:** support font scaling. Line heights adapt to script height (2026).

---

## 9. Suggested CSS token skeleton (web)
```css
:root{
  /* shape */
  --md-sys-shape-corner-none:0; --md-sys-shape-corner-extra-small:4px; --md-sys-shape-corner-small:8px;
  --md-sys-shape-corner-medium:12px; --md-sys-shape-corner-large:16px; --md-sys-shape-corner-large-increased:20px;
  --md-sys-shape-corner-extra-large:28px; --md-sys-shape-corner-extra-large-increased:32px;
  --md-sys-shape-corner-extra-extra-large:48px; --md-sys-shape-corner-full:9999px;
  /* state */
  --md-sys-state-hover:.08; --md-sys-state-focus:.10; --md-sys-state-pressed:.10; --md-sys-state-dragged:.16;
  /* motion (official cubic fallbacks) */
  --md-sys-motion-spring-fast-spatial: cubic-bezier(.42,1.67,.21,.90);  --md-sys-motion-spring-fast-spatial-duration:350ms;
  --md-sys-motion-spring-default-spatial: cubic-bezier(.38,1.21,.22,1); --md-sys-motion-spring-default-spatial-duration:500ms;
  --md-sys-motion-spring-slow-spatial: cubic-bezier(.39,1.29,.35,.98);  --md-sys-motion-spring-slow-spatial-duration:650ms;
  --md-sys-motion-spring-fast-effects: cubic-bezier(.31,.94,.34,1);     --md-sys-motion-spring-fast-effects-duration:150ms;
  --md-sys-motion-spring-default-effects: cubic-bezier(.34,.80,.34,1);  --md-sys-motion-spring-default-effects-duration:200ms;
  --md-sys-motion-spring-slow-effects: cubic-bezier(.34,.88,.34,1);     --md-sys-motion-spring-slow-effects-duration:300ms;
}
@supports (transition-timing-function: linear(0,1)){ /* override with §4.3 linear() values */ }
```
Color: generate `--md-sys-color-*` with MCU or Theme Builder, for light, dark and the medium/high contrast levels. Use §1.1 as the role list and the baseline fallback.

---

## 10. Unverified / gaps
- **Google Sans Flex:** exact axes and web licensing.
- **Margins:** medium and larger margin (24dp) and pane spacer values.
- **Disabled-state opacities:** 12% and 38%.
- **Mappings and defaults:**
  - FAB menu item color mapping.
  - Floating toolbar elevation and shadow.
  - Carousel default radius.
  - Unselected toggle-button colors.
- **Legacy pairings:** enter and exit durations.
- **Shapes on the web:** a JS port of `androidx.graphics.shapes` / MaterialShapes geometry. No official web package was found.
- **Measurement images:** the m3 specs pages for nav bar, toolbar and FAB menu show measurements as images. Numbers here come from Compose tokens, which are generated from the same design tokens.
- **Chips:** the newer generic `ChipsTokens` shape set (12dp, full when selected) is not in published guidance.

## 11. URLs actually read
**m3.material.io** (rendered in a browser):
- https://m3.material.io/styles/motion/overview/how-it-works
- https://m3.material.io/styles/motion/overview/specs
- https://m3.material.io/styles/shape/overview-principles
- https://m3.material.io/styles/typography/overview
- https://m3.material.io/styles/typography/fonts
- https://m3.material.io/styles/color/roles
- https://m3.material.io/styles/color/system/overview
- https://m3.material.io/styles/color/system/how-the-system-works
- https://m3.material.io/styles/elevation/tokens
- https://m3.material.io/foundations/interaction/states/state-layers
- https://m3.material.io/foundations/layout/breakpoints/overview
- https://m3.material.io/foundations/layout/breakpoints/compact
- https://m3.material.io/foundations/designing/structure
- https://m3.material.io/blog/building-with-m3-expressive
- Component overview pages: navigation-bar, button-groups, buttons, toolbars, icon-buttons, floating-action-button, fab-menu, extended-fab, loading-indicator, progress-indicators, app-bars, navigation-rail, split-button, lists, sliders, carousel, search, segmented-buttons, chips, tabs (all under https://m3.material.io/components/…/overview)
- Spec pages: https://m3.material.io/components/sliders/specs, /navigation-bar/specs, /toolbars/specs

**Other official sources:**
- https://design.google/library/expressive-material-design-google-research
- https://developer.android.com/develop/ui/compose/layouts/adaptive/use-window-size-classes
- AndroidX Compose token sources at `raw.githubusercontent.com/androidx/androidx/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/tokens/`:
  - System tokens: ExpressiveMotionTokens, StandardMotionTokens, MotionTokens, MotionSchemeKeyTokens, ShapeTokens, TypeScaleTokens, TypographyTokens, TypefaceTokens, ColorLightTokens, ColorDarkTokens, PaletteTokens, ElevationTokens, StateTokens, ScrimTokens.
  - Component tokens: Button{XSmall, Small, Medium, Large, XLarge}Tokens, {XSmall, Small, Medium, Large, XLarge}IconButtonTokens, ButtonGroupSmallTokens, ConnectedButtonGroupSmallTokens, SplitButton{Small, Medium}Tokens, Fab{Baseline, Small, Medium, Large}Tokens, ExtendedFab{Small, Medium, Large}Tokens, FabMenuBaselineTokens, DockedToolbarTokens, FloatingToolbarTokens, NavigationBar{,Horizontal,Vertical}ItemTokens, NavigationRail{Collapsed, Expanded, VerticalItem}Tokens, AppBar{,Small, MediumFlexible, LargeFlexible}Tokens, LoadingIndicatorTokens, Linear/CircularProgressIndicatorTokens, SliderTokens, SwitchTokens, ListTokens, ExpandedListTokens, SegmentedMenuTokens, SearchBarTokens, SearchViewTokens, SheetBottomTokens, DialogTokens, SnackbarTokens, ChipsTokens, AssistChipTokens, FilledCardTokens, ElevatedCardTokens, OutlinedSegmentedButtonTokens, PrimaryNavigationTabTokens.
- AndroidX Compose sources: MaterialShapes.kt, MotionScheme.kt, MaterialTheme.kt, LoadingIndicator.kt, ButtonGroup.kt, Button.kt, ToggleButton.kt, IconButton.kt, FloatingActionButton.kt, FloatingActionButtonMenu.kt, FloatingToolbar.kt, ShortNavigationBar.kt, carousel/Carousel.kt.
- material-color-utilities: `typescript/dynamiccolor/color_spec_2021.ts` and `color_spec_2025.ts` (github.com/material-foundation/material-color-utilities).
- material-web: the tokens directory listing (`tokens/versions` → `v0_192`) and `elevation/internal/_elevation.scss` (github.com/material-components/material-web).
- m3.material.io pages are a JS SPA: WebFetch returned 404 or empty for them, so I read them in a real browser.

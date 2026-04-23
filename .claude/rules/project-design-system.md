# YokhanVPN Dashboard Design System

Source: `preview/dashboard.html` (verified line-by-line, 2026-04-23)

---

## Color Tokens

All defined in `:root` (lines 28–64).

### Base palette (OFF / light state)

| Token        | Value                                                     | Purpose                                                                                                                           |
| ------------ | --------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------- |
| `--bg`       | `#f3f3f7`                                                 | Page background                                                                                                                   |
| `--card`     | `#ffffff`                                                 | Card surface (not actively used — cards are transparent in current CSS)                                                           |
| `--t1`       | `#111117`                                                 | Primary text, hamburger bars, signal bars                                                                                         |
| `--t2`       | `#6c6c74`                                                 | Secondary text, toolbar system label, speed labels, signal bar color in OFF state                                                 |
| `--t3`       | `#a3a3ab`                                                 | Tertiary text, orb label, speed unit color, stats bar icons, status line text                                                     |
| `--t4`       | `#ccccd2`                                                 | Disabled elements, CSS square decorators, inactive bar background                                                                 |
| `--accent`   | `#e55a1b`                                                 | Orange — active profile bar, speed arrows in OFF state, config card status dot, "ДОБАВИТЬ" button, wave graph stroke in OFF state |
| `--accent15` | `rgba(229, 90, 27, 0.15)`                                 | Defined but not directly used in visible elements                                                                                 |
| `--accent40` | `rgba(229, 90, 27, 0.4)`                                  | Defined but not directly used in visible elements                                                                                 |
| `--green`    | `#28b44f`                                                 | Connected-state accent — status dot, active profile bar, speed arrows, wave graph stroke, ring border                             |
| `--green15`  | `rgba(40, 180, 79, 0.15)`                                 | Defined but not directly used in visible elements                                                                                 |
| `--green30`  | `rgba(40, 180, 79, 0.3)`                                  | Defined but not directly used in visible elements                                                                                 |
| `--red`      | `#e53e3e`                                                 | ОТКЛЮЧЕНО status text, status dot in OFF state                                                                                    |
| `--divider`  | `rgba(0, 0, 0, 0.05)`                                     | Defined; profile item borders use `rgba(0,0,0,0.05)` inline                                                                       |
| `--shadow`   | `0 1px 2px rgba(0,0,0,0.03), 0 4px 16px rgba(0,0,0,0.04)` | Defined but not applied to any element in current CSS                                                                             |

### Space theme tokens (ON / connected state)

| Token            | Value                      | Purpose                                                                         |
| ---------------- | -------------------------- | ------------------------------------------------------------------------------- |
| `--bg-space`     | `#0a0a18`                  | Dark page background, `.space-bg` fill                                          |
| `--t-space`      | `#e8e8f0`                  | Primary text in connected state                                                 |
| `--t2-space`     | `#8888a0`                  | Secondary text in connected state                                               |
| `--t3-space`     | `#5555668`                 | Defined (note: appears to be a typo in source — 7 digits) — not visibly applied |
| `--border-space` | `rgba(255, 255, 255, 0.1)` | Card borders, stats bar border, profile item dividers in connected state        |

---

## Typography

### Font families

| Token  | Value                                 | Purpose                                                                                                                                                                                                                                                                                                                        |
| ------ | ------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `--f`  | `"Golos Text", system-ui, sans-serif` | Default body font. Loaded via local `@font-face` from `refs/golos_text.ttf`. Used for: page body, toolbar heading (h1), test button                                                                                                                                                                                            |
| `--fm` | `"JetBrains Mono", monospace`         | Monospace font. Loaded via Google Fonts (weights 400, 500, 600). Used for: system status bar, toolbar sys label, config card tag + protocol text, speed labels + values + units + arrows, orb label, status line text + timer, profile header, profile names + protocol subtitles, stats bar values + headings, toggle buttons |

### Font size tokens

| Token     | Value  | Usage                                                                                                                                                                                                                                                                     |
| --------- | ------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `--fs-xs` | `10px` | System status bar, speed labels (`.sp .lb`), config card tag, stats bar values (`.sg .v`), stats bar headings (`.sg .h`), signal strength dot indicator size reference, toggle buttons                                                                                    |
| `--fs-sm` | `12px` | Config card protocol text (`.cc .mt`), speed values (`.sp .vl`), speed arrows (`.sp .ar`), orb label (`.orb-c .lbl`), status line text (`.sl .tx`, `.sl .tm`), profile header title (`.pf-h .tt`), profile protocol subtitle (`.pi .inf .p`), stats bar icons (`.sg .ic`) |
| `--fs-lg` | `16px` | Profile names (`.pi .inf .n`)                                                                                                                                                                                                                                             |
| `--fs-xl` | `18px` | Config card name (`.cc .nm`), toolbar heading (`.tb h1`)                                                                                                                                                                                                                  |

### Where JetBrains Mono is used (complete list)

- System status bar (`.sysbar`) — time, network, battery
- Toolbar system indicator (`.tb .sys`) — "СИСТЕМА · 01"
- Config card tag (`.cc .tag`) — "ТЕКУЩАЯ КОНФИГУРАЦИЯ"
- Config card protocol line (`.cc .mt`) — "VLESS · Подписка #…"
- Speed panel labels (`.sp .lb`) — "ОТПРАВКА", "ПОЛУЧЕНИЕ"
- Speed panel values (`.sp .vl`) — numeric speed
- Speed panel unit suffix (`.sp .vl em`) — "В/с", "кБ/с"
- Speed panel arrows (`.sp .ar`) — "↑", "↓"
- Orb center label (`.orb-c .lbl`) — "ПУСК"
- Status line text (`.sl .tx`) — "НАЖМИТЕ ДЛЯ ПОДКЛЮЧЕНИЯ"
- Status timer (`.sl .tm`) — "00:00:00"
- Profile list header (`.pf-h .tt`) — "КОНФИГУРАЦИИ"
- Profile names (`.pi .inf .n`) — "Bridge-igor", etc.
- Profile protocol subtitles (`.pi .inf .p`) — "VLESS"
- Stats bar values (`.sg .v`) — "103 МС", "ОТКЛЮЧЕНО", "WIREGUARD"
- Stats bar headings (`.sg .h`) — "ЗАДЕРЖКА", "СТАТУС", "ПРОТОКОЛ"
- Toggle buttons (`.tog button`) — dev-only UI

---

## Spacing Tokens

| Token     | Value  |
| --------- | ------ |
| `--sp-s`  | `4px`  |
| `--sp-m`  | `8px`  |
| `--sp-l`  | `12px` |
| `--sp-xl` | `18px` |

### Padding rules by component

- **Config card** (`.cc`): `padding: var(--sp-l)` = 12px all sides
- **Profile list** (`.pf`): `padding: var(--sp-l)` = 12px all sides
- **Profile header** (`.pf-h`): `margin-bottom: var(--sp-s)`, `padding-bottom: 4px` (hardcoded, not token)
- **Profile items** (`.pi`): `padding: var(--sp-l) 0` = 12px top/bottom, 0 left/right
- **Connect zone** (`.cz`): `padding: var(--sp-l) var(--sp-xl) 0` = 12px top, 18px sides, 0 bottom
- **Stats bar** (`.sb`): `padding: var(--sp-m) var(--sp-s) var(--sp-l)` = 8px top, 4px sides, 12px bottom
- **Status line** (`.sl`): `padding: var(--sp-l) var(--sp-l) var(--sp-s)` = 12px top, 12px sides, 4px bottom
- **Toolbar** (`.tb`): `padding: var(--sp-m) var(--sp-xl) var(--sp-l)` = 8px top, 18px sides, 12px bottom
- **Cards** (`.card`): `margin: var(--sp-s) var(--sp-l)` = 4px top/bottom, 12px left/right; no internal padding (padding is on children)

---

## Components

### System Status Bar (`.sysbar`)

- `display: flex; justify-content: space-between; align-items: center`
- `padding: var(--sp-s) var(--sp-l) var(--sp-s)` = 4px 12px
- Font: `var(--fm)`, size `var(--fs-xs)`, weight 600
- Left: time string (plain text)
- Right (`.r`): network speed, signal, battery — gap `var(--sp-s)`, color `var(--t2)`
- Battery (`.batt`): `border: 1.2px solid var(--t2); border-radius: 2px; padding: 0 var(--sp-s)`

### Toolbar (`.tb`)

- `display: flex; align-items: center; justify-content: space-between`
- Hamburger (`.ham`): 22×16px, 3 `<i>` bars each 2px tall, `background: var(--t1)`, `opacity: 0.55`
- Title (`.tb h1`): `var(--fs-xl)`, weight 500, letter-spacing `-0.02em`
- System label (`.tb .sys`): `var(--fm)`, `var(--fs-sm)`, `var(--t2)`, weight 500; nested `<b>` uses `var(--accent)` at `var(--fs-xs)`

### Card wrapper (`.card`)

- `background: transparent`
- `border-radius: 10px`
- `margin: var(--sp-s) var(--sp-l)`
- `border: 1px solid rgba(0, 0, 0, 0.08)` (becomes `var(--border-space)` when connected)

### Config Card (`.cc`)

- `padding: var(--sp-l)` — 12px all sides
- `display: flex; justify-content: space-between; align-items: flex-start`

**Tag (`.cc .tag`)**

- Font: `var(--fm)`, size `var(--fs-xs)`, weight 600, letter-spacing `0.06em`, color `var(--t3)`
- `display: flex; align-items: center; gap: 4px`
- CSS square via `::before`: `content: ""`, width/height 6px, `background: var(--t4)`, `border-radius: 1px`, `flex-shrink: 0`, `margin-top: -1px`

**Name (`.cc .nm`)**

- `var(--fs-xl)`, weight 500, letter-spacing `-0.02em`, `margin: var(--sp-s) 0`, `line-height: 1.1`

**Protocol text (`.cc .mt`)**

- `var(--fm)`, `var(--fs-sm)`, `var(--t2)`, letter-spacing `0.01em`

**Signal icon (`.cc .sig`)**

- 44×44px, `border: 1px solid rgba(0,0,0,0.06)`, `border-radius: var(--sp-l)` (12px)
- Contains `.bars`: 4 `<i>` elements, width 4px, heights 5px/9px/14px/6px (4th is `opacity: 0.1`)
- CSS circle dot `::after`: `position: absolute; top: 5px; right: 5px; width/height: 5px; border-radius: 50%; background: var(--accent)` (becomes `var(--green)` when connected)

### Connect Zone (`.cz`)

`display: flex; align-items: center; justify-content: space-between`
`padding: var(--sp-l) var(--sp-xl) 0`

**Orb wrapper (`.orb-w`)**

- 192px × 192px, `flex-shrink: 0`, `cursor: pointer`, `overflow: visible`
- `display: flex; align-items: center; justify-content: center`
- Clicking calls `toggle()`

**Orb background (`.orb-bg`)**

- `position: absolute; inset: 0; border-radius: 50%`
- OFF gradient: `linear-gradient(145deg, #ffffff 0%, #f0f0f4 50%, #e8e8ee 100%)`
- OFF shadow: neumorphic — outer + inner light/shadow
- ON gradient: `radial-gradient(circle at 50% 45%, #1c1c34 0%, #121224 40%, #0a0a18 100%)`
- ON shadow: dark neumorphic

**Orb ring (`.orb-r`)**

- `position: absolute; inset: 8px; border-radius: 50%`
- OFF: `border: 1px solid rgba(229, 90, 27, 0.18)`
- ON: `border-color: rgba(40, 180, 79, 0.35)`, plus `animation: ring-pulse 1600ms ease-in-out infinite`

**Orb center label (`.orb-c .lbl`)**

- `var(--fs-sm)`, weight 500, `var(--t2)`, letter-spacing `0.12em`
- Disappears on connect: `opacity: 0; pointer-events: none`

**Orb ring marks (`.marks`)**

- 12 `<i>` elements (`.m0`–`.m11`) positioned around the circumference
- Alternating 3px and 2.5px circles, `background: var(--t4)`, `opacity: 0.4`
- Connected: `background: var(--green)`, `opacity: 0.45`

**Rocket group (`.rkt-group`)**

- `position: absolute; left: 50%; top: 50%`
- OFF: `transform: translate(-50%, -48%) translateY(70vh); opacity: 0` — rocket hidden below viewport
- ON: `transform: translate(-50%, -48%); opacity: 1` — rocket centered in orb
- Transition: `opacity 0.4s ease-out`, `transform 0.7s cubic-bezier(0.2, 0.8, 0.3, 1)`
- Contains: `.rkt` image + `.flm.flm-l` image + `.flm.flm-r` image

**Rocket image (`.rkt`)**

- `width: 132px`, `filter: drop-shadow(0 4px 14px rgba(0,0,0,0.12))`, `pointer-events: none`
- Source: `refs/rocket.png`
- Inside `.rkt-group`: `display: block; position: relative; z-index: 1`

**Flames (`.flm`)**

- `position: absolute; bottom: -80px; z-index: 2`
- `width: 22px`, `opacity: 0` (OFF), `opacity: 1` (ON)
- `transform-origin: top center`
- `filter: drop-shadow(0 10px 24px rgba(255, 120, 0, 0.7))`
- Source: `refs/flame.png`
- `.flm-l`: `left: calc(50% - 10px); transform: translateX(-50%)` → approximately 9px left of center
- `.flm-r`: `left: calc(50% + 8px); transform: translateX(-50%)` → approximately 8px right of center
- Connected animation: `flame-breathe 420ms ease-in-out infinite` — scaleY(1→1.15), scaleX(1→1.06)

**Speed panels (`.sp`)**

- `width: 80px`
- Left panel (`.sp:first-child`): ОТПРАВКА — all text and wave `text-align: left; justify-content: flex-start`
- Right panel (`.sp:last-child`): ПОЛУЧЕНИЕ — all text and wave `text-align: right; justify-content: flex-end`
- Label (`.sp .lb`): `var(--fm)`, `var(--fs-xs)`, `var(--t3)`, weight 500, letter-spacing `0.08em`
- Value row (`.sp .vl-row`): `display: flex; align-items: center; gap: var(--sp-s); margin: var(--sp-s) 0; white-space: nowrap`
- Value (`.sp .vl`): `var(--fm)`, `var(--fs-sm)`, weight 500, letter-spacing `-0.02em`
- Unit suffix (`.sp .vl em`): `var(--fs-xs)`, weight 500, `var(--t2)`, `font-style: normal`
- Arrow (`.sp .ar`): `var(--fm)`, `var(--fs-sm)`, weight 500, `margin-left: 1px`; color `var(--accent)` (OFF) or `var(--green)` (ON)

**Wave graphs (`.sp .wv`)**

- `margin-top: var(--sp-s); opacity: 0.25`
- SVG: `width: 56px; height: 8px; viewBox="0 0 56 10"`
- Path: sine-wave polyline, `stroke-width: 1.1`
- Stroke: `var(--accent)` (OFF), `var(--green)` (ON — via `!important`)
- Left panel: `text-align: left`; right panel: `text-align: right`

### Status Line (`.sl`)

- `display: flex; align-items: center; justify-content: center`
- `padding: var(--sp-l) var(--sp-l) var(--sp-s)`
- Status text (`.sl .tx`): `var(--fm)`, `var(--fs-sm)`, weight 400, letter-spacing `0.02em`, `var(--t3)`; text set by JS
- Timer (`.sl .tm`): `var(--fm)`, `var(--fs-sm)`, weight 400, letter-spacing `0.04em`, `var(--t3)` — hidden OFF (`width: 0; opacity: 0`), visible ON (`width: auto; opacity: 1`)
- ON: `justify-content: space-between` (pushes text and timer to opposing sides)

### Test Button (`.tb-btn`)

- `display: flex; align-items: center; justify-content: center; gap: var(--sp-s)`
- `margin: var(--sp-l) auto var(--sp-m)`
- `padding: var(--sp-m) var(--sp-l)` = 8px 12px
- `border: 1px solid rgba(0,0,0,0.1); border-radius: 20px; background: transparent`
- Font: `var(--f)` (Golos Text), `var(--fs-xs)`, weight 500, `var(--t2)`, letter-spacing `0.06em`
- Connected: gets inset box-shadow, `rgba(255,255,255,0.04)` background, border `rgba(255,255,255,0.08)`

### Profile List (`.pf`)

- `padding: var(--sp-l); background: transparent`

**Profile header (`.pf-h`)**

- `display: flex; justify-content: space-between; align-items: center`
- `margin-bottom: var(--sp-s); padding-bottom: 4px` (4px is hardcoded, not a token)
- Title (`.pf-h .tt`): `var(--fm)`, `var(--fs-sm)`, `var(--t2)`, weight 600, letter-spacing `0.06em`
  - `display: flex; align-items: center; gap: 4px`
  - CSS square `::before`: identical to `.cc .tag::before` — 6px, `var(--t4)`, `border-radius: 1px`, `margin-top: -1px`
- Add button (`.pf-h .ad`): `var(--fm)`, `var(--fs-sm)`, weight 700, `var(--accent)`; prepends `"+ "` via `::before`

**Profile items (`.pi`)**

- `display: flex; align-items: center; min-height: 42px; padding: var(--sp-l) 0`
- `border-top: 1px solid rgba(0,0,0,0.05)` — first item has `border: none`
- Connected: `border-color: var(--border-space)`

**Active bar (`.pi .br`)**

- `width: 2px; height: 42px; border-radius: 2px; margin-right: var(--sp-l); flex-shrink: 0`
- `.br.on`: `background: var(--accent)` (OFF) or `var(--green)` (ON)
- `.br.off`: `background: transparent`

**Dots (`.pi .dt`)**

- `display: none` — dots are always hidden. CSS classes `.dt.on/.off` exist with colors defined but the element is never shown.

**Profile info (`.pi .inf`)**

- `flex: 1`
- Name (`.pi .inf .n`): `var(--fm)`, `var(--fs-lg)` (16px), weight 600, letter-spacing `-0.01em`
- Protocol (`.pi .inf .p`): `var(--fm)`, `var(--fs-sm)`, `var(--t3)`, `margin-top: var(--sp-s)`, letter-spacing `0.02em`

**Action buttons (`.pi .act`)**

- `display: flex; gap: var(--sp-l); align-items: center`
- Each `button`: `background: none; border: none; color: var(--t4); padding: var(--sp-s)`
- SVG icons: 18×18px — pencil icon and 3-dot vertical menu, drawn inline with `currentColor`

### Stars Background (`.stars`)

- `position: absolute; inset: 0; overflow: hidden; pointer-events: none; z-index: 20; isolation: isolate`
- Stars are hidden by default (`top: 110vh; opacity: 0`) and animate only when `body.connected`
- Animation: `star-fly linear infinite` — `translateY(-110vh)` to `translateY(10vh)`
- Star opacity controlled via `--star-o` CSS custom property per element

**Dot stars (`.star`, no `.streak`)**

- `border-radius: 50%; background: white`
- Width/height: 1–3px (varies per element)
- `animation-duration`: 8–16s (slow movement)
- Opacity `--star-o`: 0.3–0.7

**Streak stars (`.star.streak`)**

- `border-radius: 1px` (slightly rounded rectangle)
- Width: 1–2px, Height: 8–20px (elongated vertically)
- `animation-duration`: 2–3.2s (fast movement, 2–5× faster than dots)
- Opacity `--star-o`: 0.3–0.5

### Space Background (`.space-bg`)

- `position: fixed; inset: 0; z-index: -1`
- OFF: `opacity: 0`; ON (`body.connected`): `opacity: 1`, `transition: opacity 0.6s`
- `background: var(--bg-space)` = `#0a0a18`
- `::before`: 35+ `radial-gradient` dot stars of 1–1.5px, static (CSS background-image, not animated)
- `::after`: `radial-gradient` green glow at bottom — `rgba(40, 180, 79, 0.12)`

### Bottom Zone (`.bz`)

- `display: none` — the bottom zone is fully removed from layout
- Contains a `.lp` launchpad (atmospheric gradient + ground glow + rocket image) but it is not visible

### Stats Bar (`.sb`)

- `background: transparent`
- `border-top: 1px solid rgba(0,0,0,0.08)` (becomes `var(--border-space)` when connected)
- `display: flex; padding: var(--sp-m) var(--sp-s) var(--sp-l)` = 8px top, 4px sides, 12px bottom
- `position: fixed; bottom: 0; left: 0; right: 0; z-index: 10`

**Order (left to right):**

1. ЗАДЕРЖКА — icon `◷` + value "103 МС"
2. СТАТУС — status dot `.d.sd` + status text (ОТКЛЮЧЕНО/ПОДКЛЮЧЕНО)
3. ПРОТОКОЛ — icon `◇` + value "WIREGUARD"

**Stat group (`.sg`)**

- `flex: 1; text-align: center; display: flex; flex-direction: column; align-items: center; gap: var(--sp-s)`
- Divider: `border-right: 1px solid rgba(0,0,0,0.07)` on all except last (becomes `var(--border-space)`)

**Row (`.sg .rw`)**

- `display: flex; align-items: center; gap: var(--sp-s)`

**Status dot (`.sg .d`)**

- `width: 6px; height: 6px; border-radius: 50%; background: currentColor; flex-shrink: 0; font-size: 0`
- `margin-top: -2px` — vertical alignment correction
- Color inherited from parent `color` property; `.d.sd` color set to `var(--green)` (ON) or `var(--red)` (OFF) via JS

**Icons (`.sg .ic`)**

- `font-size: var(--fs-sm); color: var(--t3); line-height: 1`
- Unicode characters: `◷` (clock, ЗАДЕРЖКА) and `◇` (diamond, ПРОТОКОЛ)

**Value (`.sg .v`)**

- `font-size: var(--fs-xs); font-weight: 700; letter-spacing: 0.03em; white-space: nowrap`
- Color: `var(--t-space)` (ON) or default `var(--t1)` (OFF)

**Heading (`.sg .h`)**

- `font-size: var(--fs-xs); color: var(--t3); letter-spacing: 0.04em; margin-top: 2px; white-space: nowrap`

---

## Alignment Rules

- All icon-plus-text rows use `display: flex; align-items: center`
- Decorative squares (config card tag, profile header) are CSS `::before` pseudo-elements — never unicode glyphs
  - Dimensions: `width: 6px; height: 6px`
  - Shape: `border-radius: 1px` (square, slightly rounded)
  - Color: `var(--t4)`
  - Vertical correction: `margin-top: -1px`
- Status dot in stats bar is a CSS element (`.sg .d`) — never a font glyph
  - Dimensions: `width: 6px; height: 6px`
  - Shape: `border-radius: 50%` (circle)
  - Vertical correction: `margin-top: -2px`
- Speed values and labels use `font-family: var(--fm)` (JetBrains Mono) exclusively
- Profile names use `font-family: var(--fm)` (JetBrains Mono)
- Body default font is `var(--f)` (Golos Text)

---

## Theme States

### OFF state (light)

- `body` has no class modifier
- Background: `var(--bg)` = `#f3f3f7`
- Primary text: `var(--t1)` = `#111117`
- Accent color: `var(--accent)` = `#e55a1b` (orange)
- Orb: light neumorphic sphere, shows "ПУСК" label
- Rocket: hidden below viewport (`translateY(70vh); opacity: 0`)
- Status text: "НАЖМИТЕ ДЛЯ ПОДКЛЮЧЕНИЯ", timer hidden
- Status dot: `var(--red)`, status value: "ОТКЛЮЧЕНО"
- Stars: hidden (`opacity: 0`, no animation)
- Space background: hidden (`opacity: 0`)

### ON state (dark / space)

- `body.connected` class applied by JS
- Background: `var(--bg-space)` = `#0a0a18` (via `.space-bg` fixed layer)
- Primary text: `var(--t-space)` = `#e8e8f0`
- Accent color: `var(--green)` = `#28b44f`
- Orb: dark radial gradient sphere, "ПУСК" label hidden
- Rocket: centered in orb (`translate(-50%, -48%); opacity: 1`)
- Flames: visible (`opacity: 1`), animate with `flame-breathe`
- Ring: green border, `ring-pulse` animation
- Ring marks: green
- Status text: "ПОДКЛЮЧЕНО", timer visible
- Status dot: `var(--green)`, status value: "ПОДКЛЮЧЕНО"
- Stars: visible and animating (`star-fly`)
- Space background: visible (`opacity: 1`)
- Launch transition: `body.launching` class briefly added — triggers `smoke-a/b/c` animations on `.smoke` blobs

### Transition timing

- Background color change: `0.6s`
- Text color change: `0.4s`
- Space background opacity: `0.6s`
- Rocket position: `transform 0.7s cubic-bezier(0.2, 0.8, 0.3, 1)` + `opacity 0.4s ease-out`
- Smoke animations (launch only): 280–360ms, not looped
- Flame breathe (connected only): `420ms ease-in-out infinite`
- Ring pulse (connected only): `1600ms ease-in-out infinite`

---

## Animations

| Name            | Duration                        | Trigger          | Effect                                                                    |
| --------------- | ------------------------------- | ---------------- | ------------------------------------------------------------------------- |
| `star-fly`      | 8–16s (dots) / 2–3.2s (streaks) | `body.connected` | Moves stars from `translateY(-110vh)` to `translateY(10vh)`               |
| `flame-breathe` | 420ms, infinite                 | `body.connected` | Scales flame scaleY(1→1.15), scaleX(1→1.06) around `top center`           |
| `ring-pulse`    | 1600ms, infinite                | `body.connected` | Pulses green glow on orb ring: `0 0 8px` → `0 0 14px rgba(40,180,79,0.2)` |
| `smoke-a`       | 320ms, once                     | `body.launching` | Smoke blob drifts left+down, fades out                                    |
| `smoke-b`       | 360ms (40ms delay), once        | `body.launching` | Smoke blob drifts right+down, fades out                                   |
| `smoke-c`       | 280ms (80ms delay), once        | `body.launching` | Smoke blob drifts down, fades out                                         |

---

## JavaScript State Machine

Two functions in the inline `<script>`:

- `sw(s)`: called by toggle buttons. `s='on'` → adds `launching` + `connected` classes, updates speed readouts, status text, status dot color. `s='off'` → removes both classes, resets all values.
- `toggle()`: called by clicking `.orb-w`. Delegates to `sw()` by simulating toggle button clicks based on current `body.connected` presence.

State storage: `body.classList` only — no localStorage, no cookies.

---

VERIFICATION:

- Approach: Read all 1847 lines of dashboard.html across 8 sequential reads; extracted every CSS rule, HTML structure, and JS behavior
- Confidence: HIGH
- Doubt: `--t3-space` value `#5555668` appears to be a 7-digit hex typo in the source — documented as-is
- Alternative considered: Skimming only the CSS section — rejected because JS state transitions and HTML structure are equally load-bearing for the design system
- Risk: If the HTML file is updated after this document is written, values will drift — this doc should be regenerated from source, not edited by hand

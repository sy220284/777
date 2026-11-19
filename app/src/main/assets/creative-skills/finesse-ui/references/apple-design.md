# Apple Design Language

Load when the user requests Apple style, 苹果风, Apple-like product storytelling, iOS-like UI, or spatial Apple-inspired materials. This is an optional design language layered over the existing register; it does not replace information architecture.

## Routing and precedence

Keep `register=brand|product|commerce|h5` and record `design-language=apple`, plus `apple-mode=editorial|app|spatial`, in the design decision and CSS stamp. For h5, retain its content register and safe-area contract. For one component, keep component scope and existing project tokens; do not start the page workflow.

| Mode | Choose for | Load next |
| --- | --- | --- |
| editorial | Product stories, launch pages, image-led features and galleries | `apple-patterns.md`; `motion.md` only for authored motion |
| app | Settings, tools, lists, forms and touch interfaces | Existing product/workflow/h5 reference; `interaction-motion.md` for gestures |
| spatial | Explicit requests for spatial windows, depth or translucent chrome | Material rules below; `3d-effects.md` only if actual 3D is needed |

Apple style alone does not select spatial mode. Bento alone does not select Apple. Preserve a user-selected language when varying composition; do not rotate into another aesthetic to satisfy the divergence quota. Within an established product, consistency takes precedence over rotation.

User instructions and the locked project system come first. The following scoped overrides supersede conflicting default aesthetic rules in the entrypoint, substrate, blacklist and preflight:

| Default | Apple override |
| --- | --- |
| Tinted neutrals only | Deliberate white/black surfaces are allowed; preserve readable surface hierarchy |
| Brand grain, vignette, dark default | Optional; clean light or dark product stages may omit all three |
| Extreme display leading and weight contrast | Use size-aware typography below, including script-specific leading |
| Mandatory brand spectacle/persona | An editorial page may ship with no hero engine; use motion only when the story needs it |
| Palette-library selection | Use the project palette or semantic tokens below instead of forcing an unrelated palette |
| Ban on default glass | Functional translucent chrome is allowed; decorative blur everywhere is still a failure |
| Generic card-grid ban | Content-weighted Bento is allowed; identical icon/title/text tiles are still a failure |
| Rejecting copied iOS chrome | Familiar control placement and system colors are appropriate when Apple style is requested; do not copy private screenshots or bundle proprietary icons |
| Same-theme-only / one accent | Intentional section inversions and product colors are allowed; actions and status colors remain consistent |

No override relaxes accessible names, keyboard operation, meaningful states, truthful content, contrast, mobile behavior, asset availability or reduced-motion handling.

## Color and material

Use semantic tokens. These are finesse starting values, not universal Apple specifications:

```css
/* finesse · register=brand · design-language=apple · apple-mode=editorial
 * A=neutral+blue · B=system-type · C=content-weighted-grid · D=feedback · E=clean-surface */
:root {
  --page: #fff; --surface: #f5f5f7; --raised: #fff;
  --ink: #1d1d1f; --muted: #606067; --accent: #0066cc;
  --line: rgb(0 0 0 / .12); --radius-card: 24px;
  --gap-x: 16px; --gap-y: 24px;
}
[data-theme="dark"] {
  --page: #000; --surface: #1d1d1f; --raised: #29292c;
  --ink: #f5f5f7; --muted: #b3b3b8; --accent: #70b8ff;
  --line: rgb(255 255 255 / .2);
}
```

Check actual foreground/background pairs, including text over photography. Product colors can vary without changing the primary action color. A black section is a deliberate composition, not an automatic inverted light theme.

Use translucency for navigation, toolbars and floating panels that need to retain context. Keep reading surfaces sufficiently opaque. Test against the busiest background; do not stack translucent text-bearing surfaces. Use an opaque base fallback before `@supports (backdrop-filter: blur(1px))`. Under `prefers-reduced-transparency: reduce`, remove blur and make the surface solid. Under `prefers-contrast: more`, strengthen the surface boundary. These preferences are independent of reduced motion.

Do not require iridescence, chromatic aberration or animated blur. Large blur animation is not compositor-free: prefer transform/opacity and profile any material animation before choosing it. A spatial web approximation is not a native visionOS implementation.

## Typography, corners and imagery

- Prefer the platform system stack with appropriate CJK fallbacks. Do not package proprietary fonts merely to resemble a platform.
- Starting display range: responsive 36–72px, line-height 1.05–1.15 for Latin; give Chinese multi-line headings more room, approximately 1.15–1.3. Body starts at 16–18px with 1.5–1.7 leading. Adjust to content and viewport; these are not mandatory dimensions.
- Tighten large Latin display text selectively. Keep body tracking near zero; do not apply one negative letter-spacing value to every size or script. Support text zoom and longer translated labels.
- Choose radii as a system. An inset surface generally uses a smaller radius than its container; a pill belongs to a compact action, not every panel. CSS `border-radius` is a fallback, not a mathematical continuous corner. Enhance `corner-shape` only with feature support.
- Match the image box to the subject. Use `contain` for complete product silhouettes and deliberate `cover` crops for editorial details. Reserve enough breathing room around the subject. A thin product does not need a square image slot.
- Sample numbers and product imagery in the bundled studies are educational composition material, not verified specifications or reusable brand assets.

## Interaction and verification

Immediate press feedback, clear focus, reversible transitions and spatially related origins matter more than decorative entrances. Tap activation still commits through normal click/keyboard semantics, not pointer-down. Read `interaction-motion.md` §8 for motion interruption and velocity handoff; avoid maintaining a second Apple gesture engine.

Use `apple-patterns.md` for layout recipes and `preflight.md` Apple gates for verification. In each first-party file that intentionally uses pure black/white, carry a `/* finesse · design-language=apple · ... */` stamp. The detector only exempts the `pure-bw` hint for that explicit file stamp; it does not certify the design, propagate exemptions through a folder, or suppress other findings.

## Public references and attribution

This module is an independent web-design adaptation, not an official Apple specification. Examples and numeric defaults are implementation starting points.

- [Apple Human Interface Guidelines](https://developer.apple.com/design/human-interface-guidelines/): platform guidance; consult the relevant platform when implementing native behavior.
- [Designing Fluid Interfaces, WWDC 2018](https://developer.apple.com/videos/play/wwdc2018/803/): interaction continuity and direct manipulation.
- [Community apple-design reference](https://github.com/attentiondotnet/emilkowalski_skills/blob/main/skills/apple-design/SKILL.md): secondary synthesis; principles were adapted, not copied wholesale or treated as official parameter tables.
- [UI UX Pro Max](https://github.com/nextlevelbuilder/ui-ux-pro-max-skill): Bento and spatial style taxonomy. Its decorative Liquid Glass recipe is not a universal Apple requirement.

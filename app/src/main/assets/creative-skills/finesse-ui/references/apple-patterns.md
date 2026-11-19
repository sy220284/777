# Apple Layout Patterns

Load after `apple-design.md` for editorial cards, feature layouts or image-led storytelling. These are adaptable composition recipes, not obligatory page templates. The bundled studies are interactive demonstrations; their inspector controls are not product UI to copy into a customer's page.

| Study | Pattern to extract | Avoid |
| --- | --- | --- |
| `../examples/apple-overview.html` | Mix image, headline and compact feature cards by information weight | Repeating one icon/title/paragraph component |
| `../examples/apple-bento.html` | One dominant product tile with supporting modules; spans express importance | Arbitrary spans or equal emphasis everywhere |
| `../examples/apple-contrast.html` | Compose background, surface and product luminance together | Blindly inverting a light theme |
| `../examples/apple-layout.html` | Separate horizontal and vertical rhythm; compare spacing interactively | Treating 16/24px as an official universal standard |
| `../examples/apple-gallery.html` | Preserve image rhythm, useful cropping, browsing and expansion | Mandatory autoplay or dragging as the only control |
| `../examples/apple-corners.html` | Compare ordinary arcs, softer transitions and light | Calling any large radius a continuous corner |
| `../examples/apple-palette.html` | White/gray/black surface relationships with deliberate accents | Heavy shadows doing all the separation |

## Content-weighted Bento

Assign semantic roles first: primary product/claim, supporting feature, compact fact, image detail. Give the primary role the largest area. Grid tracks use `minmax(0,1fr)` and children `min-width:0`. Reflow into two or one columns based on content fit; remove desktop row spans and fixed heights when they clip text. DOM order must remain a useful reading and keyboard order after reflow. Do not use dense placement to change the story invisibly.

Typical starting values: card radius 16–24px, horizontal gap 16px, vertical gap 16–24px. The demonstration also exposes other values to make comparison possible. Let image proportion and text length determine final geometry. A small card may carry one fact; it cannot carry a paragraph merely because the grid has a spare cell.

## Contrast and spacing

Separate page, surface and foreground into tokens before designing the dark counterpart. A black product on black needs controlled edge light or a contrasting image background. Text still needs AA contrast; muted does not mean illegible. Switching or reordering sections should preserve focus and the meaning of primary actions.

Keep the optical distance between content groups intentional. Internal padding, outer gap and image breathing room are three different decisions. Nested radii should follow the inset relationship rather than sharing one number everywhere.

## Gallery and expansion

Keep important subjects inside intentional crops. Use native overflow/scroll-snap for ordinary rails; use a drag engine only for behavior that needs it. Always provide buttons and keyboard access alongside drag, and preserve vertical scrolling on touch.

The flowing gallery study is an optional editorial effect. Production autoplay requires pause controls, stops while the user is interacting, and is off under reduced motion. Duplicate loop items must be removed from the accessibility tree and tab order. Opening details pauses movement; closing returns focus to a visible source. Use descriptive alt text, an accessible dialog name and Escape dismissal.

## Reuse boundary

The seven studies depend on `examples/apple/` and bundled `examples/lib/` files; keep those relative dependencies together. Read `examples/apple/ASSETS.md` before reusing media. Use the project's own approved content in production. No customer identifiers, filesystem locations, private screenshots or source-machine metadata belong in a distributable example.

These studies cover editorial patterns, not a proof that app or spatial mode has been implemented. For app work use the existing product/h5 examples and gesture lab, with the Apple typography and surface rules applied.

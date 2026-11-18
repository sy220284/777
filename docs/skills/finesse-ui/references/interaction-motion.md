# Interaction Motion — How the Interface Answers the Hand

> **The split this file exists to make.** `motion.md` covers motion the viewer *watches* — the page moves, the hand does not. This file covers motion the viewer *causes* — a finger is down, and every frame until it lifts is a negotiation. They are different disciplines with different failure modes, and the second one is where App / 小程序 interfaces are actually judged.

| | `motion.md` | **this file** |
|---|---|---|
| Who moves first | the page | the hand |
| Timeline owner | the author (duration, delay, easing) | **the user** (position, velocity, direction) |
| Can be authored blind | yes — it plays the same every time | **no** — you must handle input you did not predict |
| Fails as | generic (particle hero, fade-ups) | **dead** (looks right in a screenshot, feels wrong in the thumb) |

**Route into this file:** the beat is driven by `pointerdown/move/up`, or it is the interface *answering* a tap. Everything else is `motion.md`.

> Running evidence for every rule below: **`../examples/h5-sheet-lab.html`** — ten sheet machines, all of them draggable, four of them shipping a same-screen counter-example. Read the machine before copying the rule.

---

## 1. THE TEST — run it on any interaction before shipping

> **一格够不够高级，看的不是它有几条动画，而是它有没有在处理输入本身的不确定性。只有 class 切换的，一眼就会被看穿。**

Four kinds of uncertainty. An interaction that handles none of them is a toggle wearing an animation:

| Uncertainty | The question it asks | Handled by |
|---|---|---|
| **Speed** | did they *place* it there, or *throw* it? | §2.3 velocity window, §2.4 dual threshold |
| **Direction** | is this a horizontal swipe or a vertical scroll? | §2.5 axis lock |
| **Threshold** | is this a tap, a drag, or a fling? | §2.4 — and it is never one number |
| **Dropped frames** | the browser coalesced 5 pointer events into 1 | §2.6 `getCoalescedEvents()` |

**The cheap tell.** If the whole interaction is `el.classList.toggle('on')` plus a CSS transition, the finger is doing nothing but starting a timer. Users cannot articulate this, but they read it instantly — it is the difference between a panel that was *pulled* out and one that was *printed* on.

**The honest exception.** A tap target that just needs `:active { transform: scale(.96) }` is finished. Not every control needs a state machine — but every control the user can *drag* does.

---

## 2. FOLLOW THE FINGER — four things, in order

Miss one and the hand feels it. The order matters: each stage assumes the one above it.

```
1:1 位移  →  越界阻尼  →  阈值  →  吸附
 track       damp        commit    settle
```

### 2.1 Tracking is 1:1 and non-negotiable

While the finger is down, the element moves **exactly** as far as the finger, in the tracked axis. Not 0.9×, not eased, not `lerp`ed toward the pointer. Any multiplier reads as lag, and lag reads as a slow phone.

```js
move(dx, dy) { set(y0 + dy); }              /* ✓ */
move(dx, dy) { set(y0 + dy * 0.85); }       /* ✗ feels broken, nobody can say why */
```

**The transition trap.** If the element still carries a CSS `transition` on `transform` from its last animated move, 1:1 tracking is impossible — every frame gets queued through the easing. Add the transition class for programmatic moves, then **strip it** so the next drag is direct:

```js
const glide = (el, cls, v, ms) => {
  el.classList.add(cls);
  el.style.transform = v;
  setTimeout(() => el.classList.remove(cls), ms || 500);   /* ← the important line */
};
```

### 2.2 Past the edge, damp — don't wall

Beyond a boundary the element still moves, just less. A hard stop reads as a bug; unlimited travel reads as no boundary at all.

| Form | Formula | Use for |
|---|---|---|
| **Flat ratio** | `d = dy * .3` | short overruns — a sheet dragged up past open |
| **Exponential** | `d = MAX * (1 - Math.exp(-dy / MAX))` | pull-to-refresh — gets heavier, never reaches `MAX` |

The exponential one is why a good pull-to-refresh feels like stretching rubber: at `dy = 90` the content has moved `46`, and it can never be dragged off the screen.

### 2.3 Velocity is a windowed measurement — this is the one everyone gets wrong

There are three ways to compute "how fast were they flicking", and **two of them are wrong**:

| | Formula | Why it fails |
|---|---|---|
| ✗ Instantaneous | `dy / (now - lastMoveTime)` | consecutive `pointermove` events can be <1ms apart — velocity spikes to 12 px/ms. **That is noise, not speed.** |
| ✗ Whole-gesture mean | `totalDy / (now - pointerDownTime)` | drag slowly for 2s, then flick — the mean is still tiny. **The flick gets averaged away.** |
| ✓ **Sliding window** | displacement over the last **~100ms** | rejects per-frame jitter *and* only looks at the moment before release |

```js
function vTracker(win) {
  const buf = [];
  return {
    reset() { buf.length = 0; },
    push(v) { buf.push([performance.now(), v]); while (buf.length > 8) buf.shift(); },
    get() {                                   /* px/ms, signed */
      if (buf.length < 2) return 0;
      const last = buf[buf.length - 1];
      let i = buf.length - 2;
      while (i > 0 && last[0] - buf[i][0] < (win || 100)) i--;
      const dt = last[0] - buf[i][0];
      return dt > 0 ? (last[1] - buf[i][1]) / dt : 0;
    }
  };
}
```

> Measured in `h5-sheet-lab.html` cell 06: the same 40px drag reports **v = 12.00** instantaneous and **v = 1.02** windowed. The threshold below is calibrated against the windowed number; feed it the instantaneous one and every twitch closes the sheet.

### 2.4 Commit is two conditions, OR'd — never one

```js
const far  = y > sheetH / 3;        /* dragged most of the way   */
const fast = v > 0.55;              /* px/ms, windowed           */
if (far || fast) close(); else springBack();
```

| Ship only… | The complaint you get |
|---|---|
| distance | *"I flicked it and it bounced back"* — the interface didn't listen |
| velocity | *"I dragged it all the way down and it stayed"* — slow, deliberate users are locked out |

Starting values: **commit at 1/3 of travel**, **fling at 0.55 px/ms**. Below the commit threshold, animate *back to rest* — never leave an element parked between states.

**Tap vs. drag** is the same problem one level down: ~8px of movement starts a drag; below that it stays a tap. A long-press drag adds a timer (~260ms) that any movement over 8px cancels — otherwise scrolling the page triggers reorder.

### 2.5 Lock the axis before you claim the gesture

Only call `preventDefault()` **after** the gesture has proven horizontal (`|dx| > |dy|` and `|dx| > 8`). Claim it earlier and the page stops scrolling inside your component — the single most common way a swipe row breaks a list.

This also decides `touch-action`: a vertical-only sheet takes `touch-action: none` on the drag handle; a horizontal swipe row takes `touch-action: pan-y` so vertical scroll still belongs to the page.

And `{ passive: true }` on `touchstart`/`touchmove`/`wheel` **unless** you actually call `preventDefault()` — then it must be `{ passive: false }` or the call is silently ignored. "My swipe doesn't work" and "scrolling is janky" are the same bug seen from two sides.

### 2.6 Frames get dropped — go get them back

A 120Hz screen samples several positions per frame; the browser dispatches only the last one. For anything that draws a *path* (handwriting, signature, freehand), straight `lineTo` between dispatched points produces visible polygons on fast strokes.

```js
const pts = e.getCoalescedEvents ? e.getCoalescedEvents() : [e];   /* recover the swallowed samples */
```

Then smooth: use each real sample as a **control point** and the midpoints between samples as endpoints of a quadratic bézier. Stroke width from velocity (`w = 5.4 - v * 7`) needs its own exponential smoothing, or the line alternates thick and thin.

### 2.7 Always handle `pointercancel`

```js
el.addEventListener('pointerup', up);
el.addEventListener('pointercancel', up);   /* never omit this */
```

Without it, an interrupted drag (a call arrives, the browser takes the gesture) leaves the element stuck mid-transform with no way back. Use `setPointerCapture` so the drag survives the pointer leaving the element.

---

## 3. SHEETS — a panel is five decisions, not one

「弹窗不是『弹出来』就结束了。」 A sheet has an **origin**, a **layer**, a **controller**, a **detent**, and a **destination**. Ship the first and skip the rest and you have a panel that was printed onto the page.

### 3.1 Origin — it comes from where the finger came from

| Form | Motion | Use when |
|---|---|---|
| **Sheet** | `translateY(102% → 0)` | triggered from the bottom; the page continues underneath |
| **Modal** | `scale(.86 → 1)` + fade, centred | you must interrupt — "answer this before continuing" |

Both are correct; they are **different things**. A bottom toolbar that opens a centred box breaks the causal chain — the user's finger went one way and the interface answered from somewhere else.

`102%`, not `100%`: at exactly 100% a box-shadow or 1px border still peeks along the bottom edge.

Always ship the **grab handle** (~34×3.5px, `border-radius: 99px`, ~22% ink). It is how the panel announces it can be dragged, before anyone tries.

### 3.2 Layer — the page behind *retreats*, it does not just dim

```css
/* ✓ the page behind, when a sheet is over it */
transform: scale(.94);
filter: blur(2.4px) brightness(.92);
```

**Depth is read from blur and recession, not from brightness.** A page that only darkens reads as *disabled* — the user thinks it stopped working, not that it moved behind something. `h5-sheet-lab.html` cell 04 runs both on screen at once; the difference is not subtle.

Keep both values restrained: `.94` and `2.4px` are enough. Blur it into mush and you lose the thing the recession was communicating — **that the page is still there.**

Nesting recurses the same rule (cell 08): when a second sheet arrives, the first one goes back `translateY(-10px)`, `scale(.94)`, `brightness(.9)`. Without it the user reads "the panel changed its contents", not "there are now two", and won't know where Back goes. **Past two layers, push a page instead.**

### 3.3 Controller — can it be dragged back?

Everything in §2 applies. The minimum: 1:1 tracking down, damped up, dual-condition dismiss (§2.4).

A sheet that can only be closed by its scrim or an X button is a dialog with rounded corners.

### 3.4 Detents — snap to the nearest, always

Two or three stops (e.g. **26% / 58% / 92%** of the container). On release, run them in this order:

1. **Snap to the nearest detent by position.**
2. **Then**, if `|v| > .5`, push one detent further in the direction of the flick.

Reverse the order and "drag slowly to a specific stop" stops working — velocity would pick the detent before position ever got a vote.

> **档和档之间是过路，不是车位。** Releasing between detents is a bug, not freedom: the next gesture has no defined starting point.

On the half detent, the number to tune is not how much the sheet covers — it is **how much of the page is still legible above it**. That is the entire reason to use a half sheet instead of pushing a page. 55–65% coverage, with at least one full row of the page still readable.

### 3.5 Destination — including the way it ends

**Expanding to full screen is one progress driving four properties** — position, `border-radius` (14 → 0), the grab handle (fade + narrow, because there is nothing left to drag), title size. Author them as four separate animations and they desynchronise; the eye reads "a second panel appeared" instead of "this panel grew". Test by watching only the handle: it must be continuously the same object, never disappear-then-reappear.

**Ending is an animation too.** 「按下去不是关掉。」 A confirm button resolves in three linked steps: the button collapses to a circle → a checkmark is *drawn* (`stroke-dashoffset: 22 → 0`, not faded in — drawing takes time, and time is what reads as "completing") → it spreads into a receipt carrying the amount. A dialog that simply vanishes on confirm sends the user to the orders page to check whether it worked.

---

## 4. TRANSITIONS — the card becomes the page

Two ways to get from a card to its detail view. One keeps the chain, one cuts it.

### 4.1 FLIP, and the two frames that make it work

```js
const a = card.getBoundingClientRect();          /* First  */
const b = host.getBoundingClientRect();          /* Last   */
page.style.transition = 'none';                  /* Invert — MUST be none */
page.style.transformOrigin = 'top left';
page.style.transform =
  `translate(${a.left - b.left}px, ${a.top - b.top}px) scale(${a.width / b.width}, ${a.height / b.height})`;
requestAnimationFrame(() => requestAnimationFrame(() => {    /* ← both frames required */
  page.style.transition = 'transform .46s var(--ease)';
  page.style.transform = 'none';                 /* Play */
}));
```

**Without the double `rAF` the browser coalesces both assignments into one style recalculation and no animation happens at all.** This is the single most common reason a hand-written FLIP "just doesn't animate".

Text fades in **after** the transform lands — during the tween it is being scaled, which means it is being distorted.

### 4.2 The counter-example: the frame of white

Closing the sheet, *then* pushing a new page, leaves one frame of empty background between them. That frame is not a rough edge — it is a **statement that these are two unrelated events**, and the user reads it as having left the page they were on. Cell 09 ships both so the difference is visible.

If the new view genuinely is a different place, push it (`translateX(100%)`) and let the old page recede to `-28%`, `scale(.94)`, under a 34% scrim. **A push where the old page doesn't move makes the new page look like a sticker.**

### 4.3 Back must be draggable

A push transition that can only be reversed by a Back button is half an interaction. One progress `t` drives both pages; drag past half to complete, release before it to spring back, and release velocity gets a vote (§2.4).

---

## 5. THE SAME RULES ABOVE 390px

This file is not phone-only. A desktop console's chrome answers the pointer the same way:

- **A sidebar collapsing 332 → 64px** must give the reclaimed width to the content — a grid that goes two columns → three. If the sidebar shrinks and the content keeps its old width, nothing was gained and the animation was decoration.
- **A menu that changes species** (rail → top bar → floating capsule) is the §3.5 problem at page scale: one progress, every property, or it reads as a different component.
- **Drawers, command palettes, and inspector panels** are sheets (§3) with a different entry edge. Origin, layer, controller, detent, destination — all five still apply.

What does *not* transfer: thumb-zone reasoning and 44px hit targets (`h5-mobile.md` §2.C, §3) are phone constraints. Pointer precision changes the sizes, not the state machine.

---

## 6. THE COUNTER-EXAMPLE TABLE

Each line is a real failure with a visible symptom. If you can't tell which side you're on, build both — that is what the lab page does.

| Doing this | Reads as | Fix |
|---|---|---|
| `classList.toggle('on')` + transition, for a draggable thing | dead; a timer, not a hand | §2 |
| Instantaneous velocity | random dismissals on every twitch | §2.3 |
| Distance threshold only | "I flicked it and it came back" | §2.4 |
| Transition left on during drag | laggy, cheap phone | §2.1 |
| No `pointercancel` handler | element stuck mid-drag forever | §2.7 |
| Scrim darkens, page behind doesn't move | the page is disabled, not behind | §3.2 |
| Second sheet just covers the first | contents changed; Back is now a guess | §3.2 |
| Releasing between detents | no defined start for the next gesture | §3.4 |
| Full-screen expansion as four animations | a different panel appeared | §3.5 |
| Dialog vanishes on confirm | "did that work?" → user checks the orders page | §3.5 |
| FLIP with a single `rAF` | no animation at all | §4.1 |
| Close sheet, then push page | one frame of white; the chain is cut | §4.2 |
| Push where the old page holds still | the new page is a sticker | §4.2 |
| Sidebar collapses, content keeps its width | decoration; nothing was reclaimed | §5 |

---

## 7. PRE-FLIGHT

Before shipping any pointer-driven interaction:

1. **Drag it slowly to the end** — does it commit?
2. **Flick it barely 20px** — does it commit?
3. **Drag it and release mid-way** — does it land on a defined state?
4. **Start a drag, then drag back to zero** — does it return cleanly?
5. **Interrupt it** (open another tab / trigger `pointercancel`) — is it stuck?
6. **Watch only the page behind** — did it recede, or just dim?
7. **Strip every transition** — is every state still reachable and readable?
8. **`prefers-reduced-motion: reduce`** — sheets appear at their end state; nothing depends on a tween having run.

Passing 1 and 2 *both* is the whole point of §2.4 — most implementations pass one.


## 8. Interruptible settling and velocity continuity

Applies to every draggable interface, including Apple app/spatial mode. A transition triggered by a tap may use CSS; an element that can be grabbed mid-flight needs explicit ownership of its live position and velocity.

1. On re-grab, stop the old animation without completing it. Capture the rendered position and current velocity, keep the grab offset, then follow the pointer 1:1. Never reset to the old logical destination.
2. On release, carry the measured signed velocity into the settling spring. On programmatic retarget, preserve the spring's current position and velocity. An ease-out tween with a spring-looking curve does not by itself preserve velocity.
3. Maintain one controller per property. Clean up old frame callbacks/listeners, and handle pointer cancellation separately from a confirmed release: restore or settle safely without committing a destructive action.
4. Use a non-bouncy settling response by default. Bounce is optional when momentum makes it meaningful. Choose a spring implementation that exposes interruption and velocity; no additional library is required for ordinary press feedback.

**Units matter.** §2.3 measures px/ms. A spring taking px/s needs `velocityPxPerSecond = velocityPxPerMs * 1000`. Do not normalize by remaining distance unless the specific API requires it; a zero remaining distance would divide by zero.

For multiple detents, choose position proximity for slow placement. For a deliberate fast release, a bounded projection can choose a reachable detent in the direction of travel. Example starting heuristic: `projected = clamp(min, max, position + velocityPxPerMs * 180)`, then choose an allowed detent. The 180ms horizon is a tunable finesse heuristic, not an Apple constant. Keep §3's proximity-first behavior for careful placement, and do not substitute projected snapping for the binary dismiss/commit decision.

With reduced motion, settle immediately or use a short non-spatial transition; retain feedback and the final usable state. Check re-grab/reverse and rapid retargeting, not just a single uninterrupted animation. The existing sheet lab demonstrates tracking and thresholds; it is not proof of velocity-continuous spring handoff.

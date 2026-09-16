---
name: mia-web-frontend
description: >
  Build and review a page in this repository's web product — the CSS design system, the four
  states, RTL, accessibility, and how the Pages preview publishes. Use it for any change that
  touches HTML, CSS or front-end JS.
roles: [tec, qc]
stacks: [web]
always: true
---

# A page in this repository

The design system is **plain CSS custom properties** in `src/styles/` — `tokens.css`,
`theme.css`, `components.css` — deliberately not Tailwind, Sass or CSS-in-JS. Read
`src/example.html` first: it is a complete page with all four states, in one file.

Plain CSS is a decision, not an omission. A repository that MIA just created has no stack yet; a
custom property is read by React, Vue, Vite and a bare `.html` alike, while a Tailwind config is
a bet on one of them. The scale is the **same scale as the Android product** — 4px spacing, the
same radii, the same type scale — so a product with both an app and a site looks like one
product.

## The recipe

1. Build from the classes that exist: `mia-page`, `mia-card`, `mia-btn`, `mia-field`, `mia-chip`,
   `mia-empty`, `mia-error`, `mia-skeleton`, `mia-loading`, `mia-row`.
2. Every colour, space, radius and font size is `var(--mia-…)` from `tokens.css`. No hex literal
   and no raw `px` in a page or component you write. If a token is missing, add it to
   `tokens.css` — one place, read by everything.
3. `theme.css` already sets `dir="rtl"`, the reset, focus rings and `prefers-reduced-motion`. Do
   not re-declare direction on the page, and do not remove a focus outline without replacing it
   with a visible one.
4. Persian copy, and the page's `lang="fa"` and `dir="rtl"` stay on `<html>`.
5. New styles go in `components.css` next to the component they belong to, never inline and never
   in a `<style>` block in the page.

## The four states, in a page

| State | What is on screen |
|---|---|
| loading | `mia-skeleton` blocks shaped like the content — never a bare spinner on white |
| empty | `mia-empty`: what would be here and the action that creates the first item |
| error | `mia-error`: what failed, in Persian, and a retry button that retries |
| content | the thing itself |

Every `fetch` has a failure path that reaches the third row. A `.catch(console.error)` is an
empty page the user cannot explain.

## Accessibility, which is cheap here and expensive later

Real `<button>` and `<a>` elements, not a `<div>` with a click handler. One `<h1>`, then heading
levels in order. A label bound to every input. An accessible name on every icon-only control.
Visible focus. Contrast that survives the theme's own dark variables.

## The live preview

`preview-web.yml` publishes this product to GitHub Pages on every push to the default branch, and
the URL goes on the repository. Two things follow:

- **Every asset path must work from a subdirectory.** Pages serves at `/<repo>/`, so a
  leading-slash `/styles/theme.css` is a broken page in production and a working one locally.
  Relative paths only.
- **No secret reaches the client.** Anything in the page is public the moment it is published:
  no API key, no token, no private endpoint, however short-lived.

## Reviewing one

States first, then tokens (a hex literal in a diff is a rejection), then the seams: a changed
class name that other pages still use, a script that assumes an element that only exists on one
page, a layout that breaks at 360px. Check it renders RTL — a page built while thinking in
left-to-right usually has one absolutely-positioned element that did not mirror.

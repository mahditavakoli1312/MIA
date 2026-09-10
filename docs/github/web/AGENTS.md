<!-- Managed by MIA — do not edit by hand (kept byte-for-byte in sync with docs/github/). -->

# AGENTS.md — conventions for this repository

Read this before you change anything. It is injected into every agent prompt, so it is short on
purpose: it answers the questions you would otherwise guess at.

## Stack

- A **web** product: HTML, CSS and JavaScript that ends up as static files.
- **The stack is not chosen yet.** If this repo has no build tooling, the issue that needs one is
  the issue that adds it — prefer **Vite** with plain TypeScript unless the issue names something
  else, and say in the PR body why you picked what you picked.
- The design system in `src/styles/` is **plain CSS custom properties** and works under any of
  them. Do not replace it with Tailwind, styled-components, or a CSS-in-JS library.
- Content language is **Persian (fa-IR)** and the page direction is **RTL**.
- The site is published to **GitHub Pages** by `.github/workflows/preview-web.yml` on every push
  to the default branch. Whatever you build must end up as static files it can find.

## Build and test — the exact commands

```
npm ci          # or npm install, the first time there is a package.json
npm run build   # what CI and the Pages publisher run; this must pass
npm test        # unit tests, when the project has them
```

If there is no `package.json` yet, this repo has no build set up and your change may be the one
that creates it. A pure HTML/CSS site needs none — in that case `index.html` at the repo root (or
in `dist/`) is the whole build, and that is a perfectly good answer.

## Where each kind of file lives

```
index.html                    ← the entry point, if this is a plain static site
src/
├── main.ts | main.js         ← the entry point, if there is a bundler
├── styles/                   ← THE DESIGN SYSTEM: tokens.css, theme.css, components.css.
│                               Import them in this order, once, at the root.
├── example.html              ← the design system in use. Read it before your first page.
├── pages/ | routes/          ← one file per page
├── components/               ← one file per reusable piece of UI
├── lib/                      ← data fetching, parsing, business logic — no DOM in here
└── assets/                   ← images, icons, fonts
tests/                        ← unit tests, mirroring src/
README.md                     ← what this is, and how to serve and build it
```

New file goes in the folder its neighbours are already in. Do not invent a layout.

## Hard rules

1. **Pages are assembled from `src/styles/`, not designed.** Use the classes in `components.css`
   — `mia-page`, `mia-card`, `mia-btn` (`--primary` / `--secondary` / `--danger`), `mia-field`,
   `mia-empty`, `mia-error`, `mia-skeleton`, `mia-loading`, `mia-chip`, `mia-row`, `mia-stack` —
   with the tokens in `tokens.css` for every colour and every number.
   **No raw hex colours, no `rgb(…)`, and no bare `px`/`rem` values in page or component styles.**
   CI greps for exactly this and fails the build naming the line. If a token you need is missing,
   add it to `tokens.css` — never inline the value.
2. **No hard-coded English in the interface.** User-visible text is Persian. Keep it out of
   JavaScript string literals where a template or a content file would do.
3. **RTL is set once, by `dir="rtl"` on `<html>`.** Never re-apply it on a page or a component —
   it mirrors twice and comes out LTR again. Do give a *specific block* (code, a URL, an English
   identifier, a table of numbers) the `mia-ltr` class when it would otherwise mangle.
4. **Semantic HTML before JavaScript.** A `<button>` for an action, an `<a href>` for a
   destination, a real `<form>` for a form, one `<h1>` per page and headings in order. A clickable
   `<div>` is not reachable by keyboard and is never acceptable.
5. **Every page has four states**: loading, empty, error-with-retry, content. A page that only
   renders the happy path is not finished. `src/example.html` shows all four.
6. **Responsive down to 360px.** No horizontal scrolling on the page body at any width — a wide
   table or code block gets its own `mia-scroll-x` container. Test narrow before you finish.
7. **Accessible by default.** Every image has `alt` (empty `alt=""` if decorative), every input
   has a `<label>`, every icon-only control has a name, and the focus ring is never removed
   without a visible replacement.
8. **No new dependency** unless the change is impossible without it. Say why in the PR body. A
   date formatter, an icon set, and a carousel are all things the platform already does.
9. **Secrets never land in the repo.** No API keys or tokens in source, in tests, or in committed
   files — and remember that anything reaching the browser is public by definition. They come
   from Actions secrets.
10. **Stay inside the issue.** Do not reformat files you did not need to change, do not rename
    things nobody asked you to rename, and never touch `.github/` unless the issue names a path
    there. A small diff that builds beats a large one that does not.
11. **Test the logic you add.** New parsing or data code in `src/lib/` gets a unit test. Pure
    markup and styling changes do not need one.
12. **The README says how to run this, and it stays true.** `README.md` carries an **`## اجرا`
    ("How to run")** section: the exact commands that take someone from a fresh clone to the site
    open in a browser — what to install, which command serves it locally, which URL and port it
    comes up on, and how to produce the production build. Real copy-pasteable commands, never a
    placeholder; if the project is plain files with no build step, say that and say how to open
    them. If that section is missing, add it in the pull request you are working on now, even if
    the issue did not ask. If your change alters how the site is built, served or configured — a
    new npm script, a changed port, a new environment variable, a new build output directory, a
    move from plain files to a bundler — that section changes **in the same pull request**. A
    change that breaks the documented way to run the site is unfinished, not "documented later".
    `README.md` is always in scope for this, even when the issue lists the files you may touch and
    the README is not one of them. If nothing about running it changed, leave the README alone.
13. **Comments explain WHY, not what.** Match the density and voice of the file you are editing.

## How to add a new page

1. Read `src/example.html`. It is this recipe, already written, in one document.
2. Create the page where its neighbours live (`src/pages/`, or a new `.html` at the root for a
   plain static site).
3. Wrap everything in `mia-page` with a `mia-page__header` and a `mia-page__body`.
4. Model the four states explicitly — render the skeleton or `mia-loading` while data is in
   flight, `mia-empty` when there is none, `mia-error` with a retry button when it fails.
5. Keep data fetching in `src/lib/` and let the page call it. A page that talks to `fetch()`
   directly cannot be tested and cannot be reused.
6. Put every colour and size through a token. If you type a `#` or a `px` in a page file, stop.
7. Check it at 360px wide and with the keyboard alone (Tab through every control) before you
   finish.
8. Run `npm run build` before you finish.

## Issue and PR shape

Issues carry `## شرح`, `## مشخصات فنی`, `## راهنمای طراحی (UI/UX)` and
`## معیارهای پذیرش` (a checkbox list). Implement every acceptance criterion, and nothing that is
not in one. The PR body says what changed and why, and names any dependency you added.

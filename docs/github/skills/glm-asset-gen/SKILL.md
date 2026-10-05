---
name: glm-asset-gen
description: >
  Generate a placeholder image, icon, illustration or empty-state graphic with GLM-Image when a
  change needs artwork the repository does not have. Use it only when the issue asks for
  imagery and none is supplied.
roles: [tec]
requires: [ZHIPU_API_KEY]
triggers:
  - "icon"
  - "illustration"
  - "placeholder image"
  - "banner"
  - "avatar"
  - "empty state"
  - "آیکون"
  - "آیکن"
  - "تصویر"
  - "لوگو"
  - "بنر"
  - "گرافیک"
---

# Generating artwork the repo is missing

Adapted from Z.ai's `glm-image-gen` (github.com/zai-org/GLM-skills), through
`.github/scripts/glm-vision.js`.

```bash
node .github/scripts/glm-vision.js image --prompt "<description>" --size 1024x1024 --save app/src/main/res/drawable/empty_tasks.png
```

`--size` must be a multiple of 32 between 1024 and 2048. Without `--save` it prints the URL and
downloads nothing.

## Reach for it rarely, and only for this

Generated artwork belongs in exactly one place: **an issue that asks for an image the project does
not have and did not supply** — an empty-state illustration, a placeholder avatar, a decorative
header. Everything else is a worse answer than what the project already gives you:

- A **UI icon** comes from the icon set this project already uses (Material Symbols on Android,
  whatever the web product imports). A generated icon does not match the other twenty and is not
  a vector.
- A **logo or any brand mark** is never generated. That is somebody's decision, not yours — say
  the issue needs one supplied.
- A **photograph of a real place, product or person** is not a placeholder, it is a claim. Don't.

## Rules for the file you commit

- **Say it is generated.** Name the file so it reads as a placeholder, and write one line in the
  PR body: what you generated, with what prompt, and that a designer should replace it.
- **Keep it small.** A 2048px PNG in a mobile repo is a megabyte nobody asked for. Generate at the
  size the layout actually renders.
- **One image.** If the issue needs a set, that is a design task for a person, not six calls.
- **Respect the project's look** — the colours and the shape language of the design system, RTL
  where direction is visible, and no embedded text. Text inside a generated image cannot be
  translated, cannot be read by a screen reader, and will be the wrong font.

If the call fails, ship the change without the image using the design system's existing empty
state, and say in the PR body that the artwork is still needed. A missing illustration is a
cosmetic gap; a blocked pull request is not.

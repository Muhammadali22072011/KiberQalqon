---
name: feedback-pitch-presentation
description: How the user wants the KiberQalqon investor pitch (pitch/index.html) written and how to verify visual fixes
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 3d9f2612-06cb-45e6-8f8f-1d6050d254a3
---

The user **personally presents** the KiberQalqon investor deck (`pitch/index.html`), so:

- **All content must be plain Uzbek**, and every technical/English term must be glossed in simple Uzbek inline (deck uses a `.gl` span, e.g. `ZIP-evasion <span class="gl">· arxiv hiylasi</span>`). The user is not a security expert presenting to experts — jargon without a plain gloss is a problem.
- **Verify visual/CSS fixes at the user's actual resolution (1920×1080)** via headless capture before claiming a fix is done — not just at the deck's native 1280×720.

**Why:** The user said "опять так" (still broken) after I claimed a phone-image overflow fix that I had only checked at 1280×720; at their real 1920×1080 it was still broken. And they repeatedly stressed content must be understandable Uzbek because they present it themselves.

**How to apply:** When editing this pitch (or any UI in this project), gloss terms in Uzbek and capture/inspect at 1920×1080 before reporting success. See [[project-kiberqalqon-pitch]].

---
name: user-chat-language
description: "User prefers to chat with Claude in Russian (conversation language, not artifact language)"
metadata: 
  node_type: memory
  type: user
  originSessionId: 330c2388-739d-4f58-a1f8-dbc8fe0f1895
---

The user communicates with Claude in Russian and asked to keep responses in Russian. They often use voice-to-text, so their messages can be garbled — interpret intent generously.

Note this is the *conversation* language only. Artifact languages are different and stay as-is:
- Cloud panel UI strings: Uzbek (see [[project_kiberqalqon_pitch]])
- Pitch deck: plain Uzbek with glossed terms (see [[feedback_pitch_presentation]])
- Android app: per-locale strings (uz / ru / default)

So: explain and discuss in Russian, but keep UI button labels / code strings in their target language when quoting them.

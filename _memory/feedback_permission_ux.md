---
name: permission-ux
description: "User wants in-app permission prompts to fire automatically/reliably, never expects to enable things by hand in system Settings."
metadata: 
  node_type: memory
  type: feedback
  originSessionId: c484a452-fb57-46ba-abea-d4afd357ad00
---

When a feature needs a runtime permission or a system toggle, the app must **prompt the
user in-app reliably**, not rely on the user manually opening system Settings.

**Why:** while debugging why his own phone didn't appear on the cloud map, the geo feature
silently failed because the location-permission dialog never showed (it was asked once-ever
and gated behind consent that came later). His reaction (RU, paraphrased): "I'm not going to
turn it on myself — make it so it asks automatically, it didn't work without that permission."

**How to apply:**
- Don't suppress permission requests with a persisted "asked once" flag — re-ask on each
  launch while the permission is still missing (use a per-session flag to avoid in-session loops).
- Request a permission at the moment its feature becomes relevant (e.g. location right after
  the community-share consent is accepted in [[kiberqalqon]] ConsentActivity), not in an
  unrelated earlier sweep.
- Be honest in the explanation: Android genuinely cannot self-grant location/notification
  permissions or flip the GPS master toggle — one user tap on "Allow" is unavoidable. Minimize
  it to a single tap; never leave the user to dig through Settings unless permanently denied.

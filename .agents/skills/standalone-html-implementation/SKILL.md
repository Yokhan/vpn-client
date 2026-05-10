---
name: standalone-html-implementation
description: Build a no-dependency standalone HTML page with inline CSS and JavaScript.
---

# Standalone HTML Implementation

Default stack:

- Semantic HTML.
- Inline CSS.
- Inline JavaScript.
- CSS variables.
- Vanilla data-driven rendering.
- No external CDN, fonts, icons, or build tools unless requested.

Implementation rules:

- Use real `<button>` for actions.
- Keep focus visible.
- Use `prefers-reduced-motion`.
- Prevent horizontal overflow.
- Make touch targets at least 44px.
- Validate inputs and edge states.
- Include clipboard fallback.

QA:

- Open directly in browser.
- Check console.
- Click all controls.
- Resize to mobile.
- Test copy buttons and keyboard tab order.

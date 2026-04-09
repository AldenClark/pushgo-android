# Update Notes

This directory stores user-facing update note source files for app update prompts.

Suggested naming:

- `vX.Y.Z.json`
- `vX.Y.Z-beta.N.json`

Current required locale set (must match app language support):

- `en`
- `de`
- `es`
- `fr`
- `ja`
- `ko`
- `zh-CN`
- `zh-TW`

Recommended structure (example):

```json
{
  "en": "Line 1\\nLine 2",
  "de": "Zeile 1\\nZeile 2",
  "es": "Linea 1\\nLinea 2",
  "fr": "Ligne 1\\nLigne 2",
  "ja": "1行目\\n2行目",
  "ko": "1번째 줄\\n2번째 줄",
  "zh-CN": "第一行\\n第二行",
  "zh-TW": "第一行\\n第二行"
}
```

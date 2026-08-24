# Android release deployment and rollback

The tag-triggered workflow signs `update-feed-v1.json` with the ECDSA P-256 key in
`PUSHGO_UPDATE_FEED_ECDSA_SIGNING_KEY_B64`. Its public key must match
`PUSHGO_UPDATE_FEED_ECDSA_P256_PUBLIC_KEY_B64`.

Published APKs live under `<deploy-root>/<stable|beta>/<versionName>/`. The active feed is
`<deploy-root>/update-feed-v1.json`.

## Rollback

Every active-feed replacement keeps both:

- `update-feed-v1.json.previous`, the immediately previous feed;
- `update-feed-v1.json.backup.<sha256>`, an immutable content-addressed snapshot.

To roll back, select a snapshot, verify its SHA-256 (and match the filename for a content-addressed
backup), copy it to a temporary file in the same deploy-root directory, verify the temporary file,
then atomically rename it to `update-feed-v1.json`. Never overwrite the selected backup. Finally run:

```bash
scripts/verify_update_feed.sh https://update.pushgo.cn/android/update-feed-v1.json --check-urls
```

If an APK issue affects only one version, remove that entry, re-sign the feed, and publish it through
the same atomic path rather than editing the live feed in place.

# Play Data safety — working notes

> Draft input for the Play Console Data safety form. **Do not answer "No data
> collected" merely because gameplay is offline**: the RevenueCat billing SDK
> transmits data off the device when the store is used. Before submitting, verify
> every row against RevenueCat's current Play Data safety guidance and the final
> build.

## What the app itself does

| Data | Where | Leaves device? | Notes |
| --- | --- | --- | --- |
| Player names | Local storage | No | Typed by players; never transmitted |
| Secret words, roles, votes, scores | Local storage | No | Never logged, never in URLs or notifications |
| Settings, word history | Local storage | No | |
| Local playtest counters | Local storage | Only if the user copies and sends them | Aggregate counts only |
| Android Auto Backup | Google backup | Possibly, under the user's backup settings | `allowBackup=true` (see DECISIONS #24). Typically not declared as "collection" by the developer, but confirm |

No ads SDK, no analytics SDK, no crash reporter, no account system, no custom backend.

## Third-party SDK: RevenueCat (+ Google Play Billing)

Active only when the user opens the store, buys, restores, or when the SDK refreshes
CustomerInfo after being configured on Android with a key. **Owner must verify.**
Likely categories:

| Play category | Likely? | Purpose | Notes |
| --- | --- | --- | --- |
| Financial info → Purchase history | Yes | App functionality (deliver and restore purchases) | Product IDs, transactions, status |
| App activity / App info & performance | Verify | Diagnostics | Depends on RevenueCat defaults (`diagnosticsEnabled` is not set by this app) |
| Device or other IDs | Yes | App functionality | Anonymous RevenueCat App User ID; check whether any device identifier is sent |

- Encrypted in transit: expected (HTTPS). Verify with RevenueCat.
- Deletion requests: describe the support route; RevenueCat supports customer deletion
  from its dashboard/API.
- Data shared vs collected: RevenueCat acts as a service provider/processor. Follow
  Google's definitions when choosing "shared".

## Not collected

Location, contacts, photos, audio (the app never records audio or does speech
recognition), messages, health, web browsing, precise device identifiers by the app
itself.

## Permissions in the build

`INTERNET`, `ACCESS_NETWORK_STATE` (merged from libraries), `VIBRATE` (haptics),
`com.android.vending.BILLING` (Play Billing Library), and a library-internal
`DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. No runtime/dangerous permissions.

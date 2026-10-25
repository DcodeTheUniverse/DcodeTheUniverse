# Android manual test checklist

**Status: NOT EXECUTED.** The build container has no Android device or emulator (no
KVM), and no Play Console access. Browser tests and mock billing **do not count** as
Android tests. Record the device, Android version, build and tester for each pass.

| # | Area | Steps | Expected | Result |
| --- | --- | --- | --- | --- |
| 1 | First launch offline | Install, turn on airplane mode, open | Home loads; full free game works; Packs shows offline/unavailable notice, no crash | ☐ |
| 2 | Paid offline | Buy a pack online, force-stop, airplane mode, reopen | Pack still unlocked and playable (SDK cache) | ☐ |
| 3 | Back gesture/button | Press back on each screen and in each phase (handover, card shown, discussion, vote, runoff, results), with a dialog open | Dialog closes first; in a round it pauses; never shows an earlier card; home minimises | ☐ |
| 4 | Background/foreground | Home button while a card is revealed, while voting, while the timer runs | Card covered; round paused; explicit Resume; timer did not run in background | ☐ |
| 5 | Rotation/tablet | Rotate on each screen; test on a tablet | Layout usable; state kept; no secret revealed | ☐ |
| 6 | Process death | Developer options → Don't keep activities, or `adb shell am kill`; reopen mid-reveal and mid-vote | "Welcome back" screen; covered; votes kept; selection cleared; timer paused | ☐ |
| 7 | Recents preview | Open Recents during a round, then on home/results | Preview blank during a round; normal elsewhere | ☐ |
| 8 | Screenshots | Try a screenshot or screen recording during a round | Blocked/black during a round; allowed on public screens | ☐ |
| 9 | Keyboard | Bluetooth keyboard: Tab through setup, Space-hold to reveal, Enter to vote | Focus ring visible; all actions reachable | ☐ |
| 10 | Text scaling | System font size largest (and 200% where available) | No clipped buttons; no horizontal scroll | ☐ |
| 11 | TalkBack | Navigate a round with TalkBack | Nothing secret announced before an intentional reveal; warning text present | ☐ |
| 12 | Purchase cancel | Start purchase, back out | "Cancelled", nothing unlocked | ☐ |
| 13 | Pending | Use a pending test payment method | "Pending"; unlocks later without restart once approved | ☐ |
| 14 | Bank verification | Payment that opens an external banking app | Returns to Bluff Club; purchase completes (singleTop) | ☐ |
| 15 | Reinstall/restore | Uninstall, reinstall, Restore | Ownership returns for the same Play account | ☐ |
| 16 | Refund | Refund an order | Access removed after refresh; game not reset | ☐ |
| 17 | Splash/icon | Cold start; check launcher icon on several launchers | Dark splash with mark; adaptive icon not clipped | ☐ |
| 18 | Safe areas | Gesture nav and 3-button nav; display cutout device | Nothing hidden under bars/cutout | ☐ |
| 19 | Haptics/sound | Toggle in settings | Respected; identical for bluffer and informed players | ☐ |

Useful commands:

```bash
pnpm android:sync && cd android && ./gradlew installDebug
adb shell am kill com.zenfracta.bluffclub      # simulate process death while backgrounded
adb shell settings put system font_scale 2.0    # large text (restore with 1.0)
```

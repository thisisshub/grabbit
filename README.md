<p align="center"><img src="docs/screenshots/hero.png" alt="Grabbit on a phone and an Android TV" width="100%"></p>

<h1 align="center">🐰 Grabbit</h1>
<p align="center"><i>Usenet, but make it couch.</i></p>

---

So here's the thing. Usenet is fast, huge and kind of magical, and using it to watch something on a Friday
night usually means: find NZB → download 8 GB → unrar → wait → finally press play. By then the popcorn's cold.

Grabbit skips all of that. Pick something, hit **Play**, and it starts in a few seconds, streaming straight off your
Usenet provider. Seeking works too: jump to the end, jump back, the rabbit keeps up.

It started as a weekend "can this even work?" project and kind of got out of hand. 🙃

## What it does

🍿 **Plays while it streams.** Nothing downloads first. It even reads through RAR and 7z archives (yes, the
password-protected 7z ones too) without unpacking them.

📺 **Lives on your TV.** A real Android TV app you drive with the remote, not a phone app stretched to 55".

🎙️ **Your phone is the remote.** Say *"Pioneer One"* into your phone and it pops up on the TV. One more tap and it's playing.

🔥 **What's new on Netflix, Prime & friends.** A home screen of what just landed and what's top today on each
service, each in its own colours. Tap a title and Grabbit looks for it on Usenet.

📚 **Shows done properly.** Seasons, episodes, a progress bar on each, *Continue Watching*, and the next episode
starts on its own.

🧠 **Doesn't pick duds.** It knows what your device can decode and skips the 4K copy your 1080p TV would turn into green soup.

## Peek inside

<table>
  <tr>
    <td align="center"><img src="docs/screenshots/phone-lanes.png" width="250"><br><sub>Everything new, one scroll</sub></td>
    <td align="center"><img src="docs/screenshots/phone-netflix.png" width="250"><br><sub>Each service gets its own page</sub></td>
    <td align="center"><img src="docs/screenshots/phone-show.png" width="250"><br><sub>Binge mode: on</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="docs/screenshots/phone-light.png" width="250"><br><sub>Light mode, for the brave</sub></td>
    <td align="center" colspan="2"><img src="docs/screenshots/tv-netflix.png" width="520"><br><sub>On the big screen</sub></td>
  </tr>
</table>

## Try it (beta!)

You'll need two things you probably already have if you're reading this:

1. a **Usenet provider** (NewsDemon, Eweka, Frugal…)
2. a **Newznab indexer** API key (NZBGeek, NZBFinder, DrunkenSlug…)

Then:

- **Phone:** grab the APK from **[Releases](../../releases/latest)**, install, open, done.
- **TV:** install the same APK with the *Downloader* app. Open Grabbit, go to Settings, and scan the QR code with your
  phone so you can type your logins on a real keyboard instead of the remote. 🙏

## Found a bug? Got an idea?

Please tell me! In the app go to **Settings → Beta feedback** (on TV, scan the QR). It opens a GitHub issue with your
device info already filled in, so all you write is what happened. Nothing private is included (no server, no passwords).

## Buy me a chai ☕

Grabbit is free and will stay free. If it made your evening a little better and you feel like saying thanks,
**[buy me a chai](https://onlychai.neocities.org/support?name=Shubhashish%20Dixit&upi=9131624460%40ybl)**
(it's also in the app under Settings). Totally optional, and it makes my day.

## The fine print

Grabbit is a personal, **educational** project, shared **for beta testing only**.

- It's **not for sale**. Please don't commercialize, rebrand or resell it.
- It **doesn't host, index or provide any content**. It only plays what *your own* Usenet provider and indexer give you.
- **You're responsible for what you watch.** Stick to stuff you have the right to watch, and follow the copyright laws where you live.
- Don't use it for piracy, harassment or anything else shady.
- It's beta software: **no warranty**, things may break, and that's why your feedback helps.

Service names belong to their owners and appear only to show where a title streams officially.
Show metadata comes from movieofthenight.com and TVmaze.

<details>
<summary>Building it yourself</summary>

JDK 17 and the Android SDK. Optional beta settings go in `local.properties` (never committed):

```
grabbit.guideKey=<movieofthenight.com API key bundled into the APK>
grabbit.feedbackRepo=<owner>/<repo> for in-app feedback
```

`./gradlew :engine:test :app:testDebugUnitTest :app:assembleRelease`
</details>

<p align="center"><sub>Made with too much chai in Bangalore. 🐇</sub></p>

# Privacy Statement

Aegis is an input method: it can see everything you type. This statement explains, in plain terms,
what Aegis does and does not do with that data. It is consistent with the
[Privacy & permissions](README.md#privacy--permissions) section of the README.

**Short version:** what you type stays on your device. Aegis has no analytics, no telemetry, and no
account, and nothing you type is ever sent anywhere. Its only network use is fetching the Chinese
dictionary pack and the optional enhancement model, and checking whether a newer one exists.

## What Aegis stores, and where

All of the following is kept **only** in the app's private storage on your device
(`filesDir`) and is never transmitted anywhere by Aegis:

- **Keystrokes and candidates** — processed on-device by the built-in decoder; not logged off-device.
- **Learned words and next-word predictions** — the on-device learning model that ranks your
  frequently/recently used words.
- **User dictionary** — words you add; you can import/export it yourself (`filesDir/userdb.txt`).
- **Clipboard history and saved phrases (常用语)** — kept locally for the clipboard panel; you can
  delete entries.

## Permissions

Aegis declares **two** Android permissions:

- **`INTERNET`** — used for the downloads and update checks described under
  [Network use](#network-use), and for nothing else.
- **`USE_BIOMETRIC`** — used **only** for the default backup password: saving that password, or
  filling it into a backup dialog, requires a biometric or screen-lock confirmation first. The
  password is stored encrypted on this device and is never transmitted.

There is **no** permission for contacts, location, microphone, storage of your personal files,
device identifiers, or similar. No keystroke ever triggers a network request, and nothing you type
is ever sent.

## Network use

Aegis goes online for two kinds of thing only: fetching a resource file, and checking whether a
newer one exists. All of it is over HTTPS.

- **The Chinese dictionary pack.** The keyboard starts this download itself the first time it opens
  with no pack installed, because no Chinese dictionary ships in the APK; you can also start or
  retry it yourself, from the prompt in the candidate strip or the dictionary card in the settings
  screen. Before downloading, Aegis fetches a small metadata file from the same GitHub release to
  learn which asset to get.
- **The optional enhancement model.** Fetched from the upstream project's GitHub release, and only
  when *you* tap to start it.
- **Update checks.** *Check for update* on the dictionary card re-fetches that metadata file; on the
  enhancement-model card it sends a `HEAD` request to the model's URL to compare version markers.
  Both happen only when you tap, and if either finds a newer file it goes straight on to download
  it. Once the dictionary pack is installed, Aegis makes no automatic request of any kind.
- As with any download, the server that hosts the file necessarily sees a normal request (for
  example, your IP address and the file requested). Aegis does not add identifiers, tracking
  parameters, or analytics to these requests, and sends none of your typing data with them.

Nothing else — no keystrokes, no candidates, no learned words, no clipboard — is ever sent.

## What Aegis does not do

- No analytics or telemetry.
- No advertising or ad networks.
- No accounts, sign-in, or cloud sync.
- No selling or sharing of data (there is no data collected to sell or share).

## Data deletion

Because everything is local, uninstalling Aegis removes its stored data. You can also clear the app's
data from Android's app settings, delete individual clipboard entries in the clipboard panel, or
manage the user dictionary from the settings screen.

## Changes

If this statement changes in a future release, the updated version will accompany that release in
this repository.

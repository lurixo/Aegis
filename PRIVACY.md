# Privacy Statement

Aegis is an input method: it can see everything you type. This statement explains, in plain terms,
what Aegis does and does not do with that data. It is consistent with the
[Privacy & permissions](README.md#privacy--permissions) section of the README.

**Short version:** what you type stays on your device. Aegis has no analytics, no telemetry, and no
account, and it makes no network requests while you type.

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

Aegis declares exactly **one** Android permission:

- **`INTERNET`** — used **only** when *you* choose to download the optional full dictionary pack or
  the optional enhancement model from the settings screen.

There is **no** permission for contacts, location, microphone, storage of your personal files,
device identifiers, or similar. The typing path never uses the network.

## Network use

The **only** time Aegis uses the network is a download **you** initiate:

- The **full dictionary pack** and the optional **enhancement model** are fetched over HTTPS from
  GitHub Releases (and, for the enhancement model, its upstream release host).
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

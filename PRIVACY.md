# Privacy Statement

Aegis is an input method: it can see everything you type. This statement explains, in plain terms,
what Aegis does and does not do with that data. It is consistent with the
[Privacy & permissions](README.md#privacy--permissions) section of the README.

**Short version:** what you type stays on your device. Aegis has no analytics, no telemetry, and no
account, and nothing you type is ever sent anywhere. The only network use in Aegis's own code is
fetching the Chinese dictionary pack and the optional enhancement model, and checking whether a
newer one exists.

## What Aegis stores, and where

All of the following is kept **only** in the app's own private storage on your device and is never
transmitted anywhere by Aegis:

- **Keystrokes and candidates** — processed on-device by the built-in decoder; not logged off-device.
- **Learned words and next-word predictions** — the on-device learning model that ranks your
  frequently/recently used words (`filesDir/userlearn.txt`). No word is learned in a field whose
  app asks for no personalized learning.
- **User dictionary** — words you add and words Aegis has learned for you; you can import/export it
  yourself (`filesDir/userdb.txt`).
- **Clipboard history and saved phrases (常用语)** — kept locally for the clipboard panel
  (`filesDir/clipboard.txt` and `filesDir/phrases.txt`). You can delete individual entries, and you
  can switch the clipboard history off altogether.
- **Which symbols and emoji you reach for** — used to fill the *Common* tab of those two panels
  (`filesDir/symbol_usage.txt` and `filesDir/emoji/symbol_usage.txt`). Nothing at all is counted in
  a field whose app asks for no personalized learning; elsewhere it records the symbol you picked
  and nothing about the field you picked it in.
- **Your settings**, including the symbols you put on the keyboard yourself and, if you chose to
  save one, the default backup password — that one encrypted with a key held by the Android
  keystore, as described under [Permissions](#permissions).

None of it leaves that storage on its own. The one way any of it does leave is a file **you**
export — a backup, your user dictionary, or your phrases. Android asks you where each file should
go, and Aegis writes it to the place you picked and nowhere else. A backup is encrypted with the
password you type for it; a dictionary or phrase export is plain text, which is what makes it
usable elsewhere.

## Permissions

Aegis's own manifest declares **two** Android permissions:

- **`INTERNET`** — used for the downloads and update checks described under
  [Network use](#network-use), and for nothing else.
- **`USE_BIOMETRIC`** — used **only** for the default backup password: saving that password, or
  filling it into a backup dialog, requires a biometric or screen-lock confirmation first. The
  password is stored encrypted on this device and is never transmitted.

The APK that Android installs lists a third, `com.aegis.ime.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`.
It is not an Android platform permission and asks for nothing on your device: the AndroidX libraries
add it when the manifests are merged, and it is declared by Aegis's own package at `signature`
protection level, so only code signed with the same key could ever hold it. It is named here so that
counting the permissions in the installed APK gives the same answer as counting them here.

There is **no** permission for contacts, location, microphone, storage of your personal files,
device identifiers, or similar. No keystroke ever triggers a network request, and nothing you type
is ever sent.

## Network use

Aegis's own code goes online for two kinds of thing only: fetching a resource file, and checking
whether a newer one exists. All of it is over HTTPS.

- **The Chinese dictionary pack.** No Chinese dictionary ships in the APK, and the keyboard never
  starts this download by itself. When you type Chinese with no pack installed, the candidate strip
  shows a download prompt; the transfer begins only when you tap that prompt, or the Download button
  on the dictionary card in the settings screen. Before downloading, Aegis fetches a small metadata
  file from the same GitHub release to learn which asset to get.
- **The optional enhancement model.** Fetched from the upstream project's GitHub release, and only
  when *you* tap to start it.
- **Update checks.** Once a file is installed, its card carries a button to check for updates: on
  the dictionary card that re-fetches the same metadata file; on the enhancement-model card it
  sends a `HEAD` request to the model's URL to compare version markers. Both happen only when you
  tap, and if either finds a newer file it goes straight on to download it. Nothing in Aegis's own
  code makes an automatic request of any kind.
- As with any download, the server that hosts the file necessarily sees a normal request (for
  example, your IP address and the file requested). Aegis does not add identifiers, tracking
  parameters, or analytics to these requests, and sends none of your typing data with them.

Nothing else — no keystrokes, no candidates, no learned words, no clipboard — is ever sent.

**One part of the APK is not Aegis's own code.** The APK carries `androidx.emoji2`, which arrives as
part of Jetpack Compose. It registers a start-up task
(`androidx.emoji2.text.EmojiCompatInitializer`) that waits for one of Aegis's own screens to be
opened and then, a moment later, looks through the packages on your device for one that offers an
emoji font: if it finds one that is part of the system image it asks that package for the font, and
where no system package offers one, nothing is asked. Typing does not trigger it; the trigger is one
of Aegis's screens being opened. The search itself is a local lookup that reaches no network. It
happens at most once for each run of the app, and nothing records that it happened, so it comes
round again the next time Android starts the app afresh. Aegis opens no connection for any of this
and sends nothing of yours with it; whether a system package that is asked then goes to the network
is that package's own behaviour in its own process — we have not measured it. It is written down
here because a statement about automatic requests should cover the whole APK and not only the part
we wrote.

## What Aegis does not do

- No analytics or telemetry.
- No advertising or ad networks.
- No accounts, sign-in, or cloud sync.
- No riding along on Android's own copying: Aegis opts out of backup, and its data is excluded from
  both cloud backup and device-to-device transfer, so none of it is carried to Google's servers or
  to a new phone by that route. Moving your data to another device is something you do deliberately,
  by exporting a backup and importing it there.
- No selling or sharing of data (there is no data collected to sell or share).

## Data deletion

Because everything is local, uninstalling Aegis removes its stored data. You can also clear the app's
data from Android's app settings, delete individual clipboard entries in the clipboard panel, or
manage the user dictionary from the settings screen.

## Changes

If this statement changes in a future release, the updated version will accompany that release in
this repository.

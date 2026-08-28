# Clipboard — macOS Clipboard History + Emoji Picker (Java/JavaFX)

A lightweight, menu-bar-only macOS application that keeps a history of everything you copy
and adds an integrated **Emoji picker** in the same popup — built entirely in
**Java 21 + JavaFX**.

```
┌──────────────────────────────────────┐
│ Clipboard     Emoji      ⌘1 2 · ⎋   │   ← tabs
├──────────────────────────────────────┤
│ Search clipboard...                  │   ← search field
├──────────────────────────────────────┤
│ Hello world                 📌  ✕    │   ← entry + pin/delete
│ Text · 10 seconds ago                │
├──────────────────────────────────────┤
│ SELECT * FROM users WHERE …   📌  ✕  │
│ Text · 2 minutes ago                 │
├──────────────────────────────────────┤
│ [🖼]                                 │
│ Image · 5 minutes ago         📌  ✕  │
└──────────────────────────────────────┘
```

Press **⌘⇧V** anywhere to open the popup near the mouse. The **Emoji** tab in the
same popup gives you a searchable, categorized emoji grid with recently-used tracking.
Selecting anything copies it and (by default) pastes it into the app you were using.

---

## Features

| Area | Details |
|---|---|
| Clipboard monitoring | Polls the macOS clipboard every 400 ms (off the UI thread); plain text, rich text (HTML) and images |
| Duplicate handling | SHA-256 content hash; ignores no-change repeats; re-copying an older item moves it to the top instead of duplicating |
| History | Configurable size (default 100); oldest **non-pinned** items are evicted first; optional retention period |
| Pin / delete / clear | Per-item pin and delete; clear history (keeps pinned); clear-all |
| Search | Case-insensitive substring search over text entries |
| Popup | Floating, always-on-top, near the mouse, closes on focus loss / Escape; keyboard navigable |
| Tabs | `Clipboard` and `Emoji` in one popup; ⌘1 / ⌘2 to switch, ←/→ when the tab bar has focus |
| Emoji picker | 1900+ offline Unicode emoji, 9 categories, recently-used ranking (frequency + recency), emoji search by name *and* keyword |
| Automatic paste | Restores focus to the previous app and synthesizes ⌘V (requires Accessibility permission); graceful copy-only fallback |
| Global hotkey | System-wide ⌘⇧V via a CoreGraphics event tap (JNativeHook), isolated behind `GlobalHotkeyService` |
| Menu bar | Runs as an accessory (no Dock icon) with a tray menu: open history, open emoji, pause, clear, settings, quit |
| Persistence | Local SQLite (`~/Library/Application Support/Clipboard/clipboard.db`); can be disabled (in-memory mode) |
| Privacy | Pause monitoring, clear history, disable persistence, retention limits, app-ignore extension point; clipboard contents are never logged |
| Settings | History size, retention, launch at login, global shortcut, auto-paste, persistence, recent-emoji options |

---

## Requirements

* **macOS** (tested on macOS 26, arm64)
* **JDK 21** (bundled into the app image when packaged; `java -version` must report 21+)
* Gradle — use the checked-in wrapper (`./gradlew`); it downloads Gradle 8.14 automatically.

Runtime dependencies (resolved by Gradle from Maven Central — the app itself needs no
network access at runtime):

| Dependency | Purpose |
|---|---|
| OpenJFX 21 (`javafx.controls`, `javafx.graphics`) | UI toolkit |
| `org.xerial:sqlite-jdbc` | embedded local database |
| `com.github.kwhat:jnativehook` | global keyboard shortcut (native event tap) |
| `net.java.dev.jna` | AppKit/CoreGraphics calls (focus restore, synthetic paste, activation policy) |
| `org.slf4j:slf4j-simple` | logging |

---

## Running

```bash
./gradlew run            # start the app (menu-bar only; no Dock icon)
./gradlew test           # run the unit tests
./gradlew build          # compile + test + jar
```

The popup opens with **⌘⇧V**. The menu-bar icon (clipboard glyph) offers:
Open Clipboard History · Open Emoji Picker · Pause Clipboard Monitoring · Clear History ·
Settings · Quit.

Development helpers:

```bash
./gradlew -Pdevpopup run   # seeds sample entries and opens the popup automatically
./gradlew -Pdevemoji run   # same, opening the Emoji tab
./gradlew -Pdevsettings run  # opens the Settings window
```

### macOS permissions

The app works without any permissions for basic clipboard *capture* (copying items into
history). Two features require **Accessibility** permission
(*System Settings → Privacy & Security → Accessibility*):

1. **Global hotkey** — JNativeHook creates a `CGEventTap` to observe key presses system-wide.
   Without permission, registration fails and the menu can still open the popup; the tray
   menu's “Open Clipboard History” works regardless.
2. **Automatic paste** — synthesizing ⌘V into another app requires posting events via
   CoreGraphics, which only succeeds for trusted processes.

Grant it once: when prompted, add your terminal (for `./gradlew run`) — or `Clipboard.app`
after packaging — in the Accessibility list. The Settings window shows the current
permission state and a button that opens the right System Settings pane.
If paste is not permitted, selection still copies to the clipboard: just press ⌘V manually.

When packaged as an `.app` (below), macOS asks for permission the first time the hotkey
or paste is attempted, and the prompt names the app.

### Launch at login

Toggling “Launch at login” writes a LaunchAgent plist
(`~/Library/LaunchAgents/local.clipboardhistory.agent.plist`) that runs the packaged
`.app` at login via `open`. It works best after packaging; in `./gradlew run` mode there
is no stable bundle path, so the entry points at the project directory.

---

## How the pieces work

### Global shortcut (`⌘⇧V`)

* `hotkey.GlobalHotkeyService` is the replaceable interface (`register`/`unregister`).
* `hotkey.MacGlobalHotkeyService` uses **JNativeHook** (a native event tap) and matches
  the parsed `ShortcutModifier` from settings. The callback is marshalled onto the JavaFX
  thread with `Platform.runLater`, which toggles the popup.
* Changing the shortcut in Settings re-registers live.

### The popup

* `ui.ClipboardPopupController` creates an undecorated, always-on-top JavaFX `Stage`
  (`StageStyle.TRANSPARENT`) with rounded, vibrant-style CSS (`resources/ui/clipboard.css`).
* It positions itself centered under the mouse (`positionNearMouse`), clamped to screen bounds.
* It closes when it loses focus or on **Escape**. Selecting an entry also closes it.
* Keyboard: ↑/↓ navigate, **Enter** or double-click selects, **Delete** removes the focused
  item, **⌘P** pins it. Typing while the Emoji tab is open focuses the search field.
* Tab switching: click the tabs, **⌘1** / **⌘2**, or ←/→ when a tab button has focus.

### Clipboard monitoring & history

* `clipboard.AwtClipboardGateway` reads/writes the system clipboard through AWT
  (`Toolkit`, `Clipboard`, `Transferable`, `DataFlavor`) — text, HTML (rich text) and images.
  Image payloads are re-encoded as PNG and a 72 px thumbnail is stored for previews, so
  large screenshots don't bloat memory or the list.
* `clipboard.ClipboardMonitor` polls every 400 ms on a daemon scheduler (the macOS
  clipboard offers no push API for passive observers).
* `clipboard.ClipboardService` performs SHA-256 dedupe (identical-to-latest is ignored;
  re-copying an older item moves it to the top), enforces the history limit by evicting the
  oldest non-pinned entries, applies the retention window, and exposes search.
* Temporary clipboard ownership errors from other apps are caught and retried on the next poll.

### Automatic paste (⌘V)

`paste.MacPasteService` + `platform.MacNative` (JNA → AppKit/CoreGraphics):

1. Before the popup opens, the frontmost application's pid is captured
   (`NSWorkspace.frontmostApplication`).
2. On selection, the popup hides, the item is written to the clipboard, focus is restored
   with `NSRunningApplication.activateWithOptions:`, and
   `CGEventCreateKeyboardEvent`/`CGEventPost` synthesizes ⌘V.
3. All of this runs on a worker thread; if Accessibility is not granted, it silently
   stops at “copied to clipboard”.

### Emoji picker

* `emoji.EmojiRepository` loads `resources/emoji/emojis.tsv` — 1906 fully-qualified
  Unicode emoji (Unicode 16 dataset) with CLDR names, category, and keyword aliases
  merged from gemoji. It is bundled, so **no network access is ever required**.
* Search matches names *and* keywords, ranked exact → prefix → contains:
  `laugh → 😂 🤣`, `heart → ❤️ 💕`, `fire → 🔥`, `check → ✅`, `rocket → 🚀`.
* Clicking/Enter copies the emoji, records it in `recent_emojis` (SQLite), closes the
  popup and pastes it.
* `RecentEmojiService` ranks recents with a combined frequency + exponential-decay
  recency score, and trims to the configurable maximum.
* Regenerate the dataset (offline, needs the two source files in `scripts/`):
  `python3 scripts/build_emoji_dataset.py`.

### Persistence

* `repository.Database` owns the SQLite connection and applies migrations via
  `PRAGMA user_version` (tables: `clipboard_items`, `settings`, `recent_emojis`).
* WAL mode; all database access is off the FX thread; the popup reloads on open.
* Turning off “Store clipboard history between restarts” switches to
  `InMemoryClipboardRepository` on the next launch.
* **Nothing is ever sent over the network.** Clipboard data lives only in
  `~/Library/Application Support/Clipboard/`.

### Privacy

* `security.PrivacyService`: global pause switch (tray + settings), oversized-payload guard,
  and an `isIgnoredApp(...)` extension point backed by the configurable ignore list
  (default: 1Password, Keychain, Bitwarden, KeePass) for future source-app filtering.
* Clipboard contents are never written to logs; only types/lengths/hashes are.

---

## Settings

Window (tray → **Settings…**): history size, retention period, global shortcut,
launch at login, automatic paste, store history between restarts, remember recently used
emojis + limit, “Clear clipboard history now”, Accessibility permission status, and an
**About** section (open-source credit and source repository link).

---

## Packaging as a `.app`

Requires a JDK with `jpackage` (11+; tested with 21) and the `packaging/icon.icns`
(generated: see `packaging/`).

```bash
./gradlew packageApp
# -> build/stage/Clipboard.app  (LSUIElement is injected automatically → no Dock icon)
```

To make it distributable, optionally build a DMG:

```bash
jpackage --type dmg --name Clipboard --app-version 1.0.0 \
         --input build/install/clipboard/lib --main-jar clipboard-1.0.0.jar \
         --main-class app.Launcher --dest build/dist \
         --icon packaging/icon.icns \
         --java-options "-Dapple.awt.UIElement=true"
```

Install the app to `/Applications`, launch it once, and grant **Accessibility** when
prompted (Settings → Privacy & Security → Accessibility).

---

## Architecture

```
src/main/java/
  app/         ClipboardApplication (JavaFX wiring), Launcher (classpath-safe entry)
  clipboard/   ClipboardMonitor, ClipboardService, ClipboardHasher, ClipboardGateway,
               AwtClipboardGateway (macOS system clipboard), ClipboardSnapshot
  model/       ClipboardItem, ClipboardContentType
  repository/  ClipboardRepository + Sqlite/InMemory impls, SettingsStore + Sqlite impl,
               Database (migrations)
  emoji/       Emoji, EmojiCategory, EmojiRepository, EmojiService,
               RecentEmojiService + Sqlite/InMemory repos
  hotkey/      GlobalHotkeyService, MacGlobalHotkeyService (JNativeHook), ShortcutModifier
  paste/       PasteService, MacPasteService
  platform/    MacNative (JNA/AppKit/CoreGraphics/Accessibility), ObjCRuntime, LaunchAtLogin
  ui/          ClipboardPopupController, ClipboardTabController, EmojiTabController,
               SettingsController, RelativeTime
  tray/        MenuBarService (SystemTray)
  security/    PrivacyService
src/main/resources/
  emoji/emojis.tsv   offline dataset (generated from Unicode 16 + gemoji)
  ui/clipboard.css   macOS-style popup theme
scripts/       build_emoji_dataset.py + source data
packaging/     icon.icns
```

SOLID highlights: clipboard access (`ClipboardGateway`), hotkeys (`GlobalHotkeyService`)
and paste (`PasteService`) are interfaces with macOS implementations isolated in
`platform/`, `hotkey/` and `paste/` — core logic (`ClipboardService`, repositories,
emoji services, settings) is pure Java and fully unit-tested without a real clipboard.

## Tests

`./gradlew test` — covers dedupe, history limits, pinning, deletion, clearing, persistence
(SQLite reopen), search, retention cleanup, emoji search/categories, recent emojis +
ranking + trim, settings persistence, hash stability and shortcut parsing.

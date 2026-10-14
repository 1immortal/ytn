<p align="center">
  <img src="docs/images/hero.png" alt="YTN, your TrueNAS companion" width="860">
</p>

<h1 align="center">YTN</h1>

<p align="center">
  <b>Your TrueNAS companion.</b><br>
  A free, ad-free Android app to keep an eye on your <b>TrueNAS</b> server and look after it from your phone.
</p>

<p align="center">
  <a href="https://github.com/1immortal/ytn/releases/latest"><b>⬇ Download the latest version</b></a>
</p>

> [!TIP]
> **New name, same app.** YTN used to be called *TrueNAS Companion*. If you already have it, just update as usual: it installs over the old version and keeps your servers and settings. Look for the new blue **Y** icon on your home screen.

## A quick tour

<table>
  <tr>
    <td align="center"><img src="docs/images/home-dark.png" width="200" alt="Home"><br><sub><b>Home</b>: health, pools and live stats</sub></td>
    <td align="center"><img src="docs/images/home-light.png" width="200" alt="Home in light mode"><br><sub>…in light mode too</sub></td>
    <td align="center"><img src="docs/images/storage-light.png" width="200" alt="Storage"><br><sub><b>Storage</b>: pools, disks, data, shares, backup</sub></td>
    <td align="center"><img src="docs/images/alerts-dark.png" width="200" alt="Alerts"><br><sub><b>Alerts</b>: snooze, dismiss, get notified</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="docs/images/system-dark.png" width="200" alt="System"><br><sub><b>System</b>: every setting on one screen</sub></td>
    <td align="center"><img src="docs/images/protection-dark.png" width="200" alt="Data protection"><br><sub><b>Backup</b>: snapshots, scrubs, sync</sub></td>
    <td align="center"><img src="docs/images/catalog-dark.png" width="200" alt="Apps"><br><sub><b>Apps</b>: install, update, logs</sub></td>
    <td align="center"><img src="docs/images/editor-dark.png" width="200" alt="Share editor"><br><sub><b>Shares</b>: simple full-screen editors</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="docs/images/services-dark.png" width="200" alt="Services"><br><sub><b>Services</b>: start, stop, settings</sub></td>
    <td align="center"><img src="docs/images/scheduled-tasks-dark.png" width="200" alt="Scheduled tasks"><br><sub><b>Scheduled tasks</b> in plain words</sub></td>
    <td align="center"><img src="docs/images/connection-dark.png" width="200" alt="Connection"><br><sub><b>Connection</b>: home, away and VPN</sub></td>
    <td align="center"><img src="docs/images/notif-mock-dark.png" width="200" alt="Phone alerts"><br><sub><b>Phone alerts</b>, straight from your NAS</sub></td>
  </tr>
</table>

<p align="center"><img src="docs/images/widget.png" width="560" alt="Home-screen widget in light and dark"><br><sub>A home-screen widget shows your NAS at a glance (it follows your light or dark theme).</sub></p>

**New in 1.12:** wake your NAS from your phone with **Wake-on-LAN** (from All servers, the "can't connect" card, a Quick Settings tile or Tasker), enjoy **tablet, foldable and landscape layouts** with a side rail and list-and-details side by side, and keep an eye on your NAS from a **Wear OS watch** with a tile, a complication and an alert list.

<p align="center">
  <img src="docs/images/tablet-two-pane-dark.png" width="420" alt="Tablet: certificate list with details side by side">
  &nbsp;
  <img src="docs/images/wake-on-lan.png" width="200" alt="All servers with reachability dots and a Wake button">
  &nbsp;
  <img src="docs/images/watch-tile.png" width="180" alt="Watch tile: NAS health, pools, alerts and CPU">
</p>

**New in 1.11:** back up and restore your NAS's configuration (with a reminder and a "back up first" step before updates), get a protection checkup with a score and one-tap fixes, move your settings to a new phone in an encrypted file, edit custom apps' compose files safely, clean up unused images, and automate YTN with Tasker.

**New in 1.10:** write your own alert rules ("tell me when a pool passes 85%" or "when a backup hasn't worked for two days"), follow scrubs and backups live in your notifications, keep nights quiet, see roughly how many months until a pool is full, and pick from four new home-screen widgets.

<p align="center">
  <img src="docs/images/alert-rules-light.png" width="220" alt="Alert rules: pool usage, disk temperature and backup rules with severity and cooldown">
  &nbsp;
  <img src="docs/images/widgets.png" width="520" alt="New widgets: dashboard, pool usage, status dot and actions, in light and dark">
</p>

<sub>Screenshots use made-up example data.</sub>

## What you can do

- 📊 **See how your NAS is doing** at a glance: health, pools, free space, apps, CPU, memory, network and temperatures, plus reports for the last hour, day, week, month or year, and a forecast of when each pool will be full.
- 🔔 **Get alerts on your phone** when a pool degrades, a disk runs hot or space runs low, straight from your NAS with no cloud service in between. Snooze or acknowledge them in one tap, add your own rules, set quiet hours, and watch scrubs and backups progress live.
- 💾 **Look after your storage**: pools and disks, datasets, SMB, NFS and iSCSI shares, a file browser, and a step-by-step guide for replacing a failing disk.
- 🛟 **Keep your data safe**: snapshots, scrubs, SMART tests, replication to another NAS and cloud sync, configuration backups, and a protection checkup that tells you what's missing.
- 🧩 **Manage apps, VMs and containers**: install and update apps, read logs, start and stop VMs, open a terminal.
- ⚙️ **Handle the everyday chores**: services, scheduled tasks, users and groups, certificates, updates, reboot and shut down.
- 🌐 **Check your network settings**: interfaces, addresses, gateways, DNS and routes at a glance, view only, so nothing on your phone can cut your NAS off the network.
- 🏠 **Works at home and away**: uses your home address on your Wi-Fi and your remote address everywhere else, with an optional built-in VPN (WireGuard or Tailscale) so your NAS doesn't have to be open to the internet.
- 🔒 **Private and secure**: sign in with your TrueNAS account (with two-factor codes), lock the app with your fingerprint, and everything stays encrypted on your phone.
- ⏰ **Wake it up**: send a Wake-on-LAN packet from your phone and see when the NAS is back online.
- 📱 **Phones, tablets, foldables and watches**: two-pane layouts on big screens, and a Wear OS app for a quick look at your wrist.
- 🎨 **Looks at home on your phone**: light and dark themes, colours from your wallpaper, a themed icon, large-font and TalkBack friendly.

Want the full list? See **[everything YTN can do](docs/FEATURES.md)**.

## Get the app

1. On your phone, open the [latest release](https://github.com/1immortal/ytn/releases/latest) and download **`truenas-companion-release.apk`**. (The file keeps its old name so that existing installs keep updating.)
2. Open the downloaded file. Android asks you to allow **Install unknown apps** for your browser or file manager. Allow it, go back, then tap **Install**.
3. That's it. YTN lets you know when there's a new version (System › About) and installs it over the old one, keeping your settings.

You need Android 8.0 or newer and TrueNAS 25.04 or newer.

**Updating from TrueNAS Companion?** Nothing to do: update from inside the app or install the new file over it. The app is now called **YTN** on your home screen, with a new icon.

<details>
<summary>Preview builds, and older versions</summary>

- **Preview builds:** `truenas-companion-debug.apk` is a separate app ("YTN Preview") with extra diagnostics. It can sit next to the normal app. Pick the matching channel in System › About › Update channel.
- **Coming from 0.x (before 1.0.0)?** Those builds used a different signing key: uninstall once, then install the release APK and add your server again.
- **HTTPS only (since 1.7.1):** the app only signs in over `https://`. TrueNAS serves HTTPS out of the box, so this usually just means using `https://` in the address.

</details>

### On your watch (Wear OS)

The watch app shows your NAS's health, a tile, a complication and your alerts. It gets everything from YTN on your phone, so it never needs your NAS password. You need a Wear OS 3+ watch paired with the phone, **Google Play services on both** (the phone and watch talk through it), and YTN 1.12 or newer on the phone.

The watch app isn't on the Play Store, so install it once from a computer:

1. Download **`truenas-companion-wear-release.apk`** from the [latest release](https://github.com/1immortal/ytn/releases/latest) to your computer, and install [Android platform tools](https://developer.android.com/tools/releases/platform-tools) (`adb`).
2. On the watch, turn on **Developer options** (Settings › System › About › tap *Build number* 7 times), then turn on **ADB debugging** and **Wireless debugging**. Keep the watch on the same Wi-Fi as your computer.
3. In *Wireless debugging*, tap **Pair new device**. On your computer run `adb pair <ip>:<pairing port>` and enter the code shown on the watch, then `adb connect <ip>:<port>` with the address shown on the *Wireless debugging* screen.
4. Run `adb install truenas-companion-wear-release.apk`. Then turn Wireless debugging off again.

Open YTN on the watch, or add the **YTN** tile and complication from your watch face. Updates to the watch app are installed the same way (`adb install -r`). The phone app's updater only updates the phone.

## Getting started

1. **Add your server.** Tap *Add server* and enter the address you use for the TrueNAS web UI, for example `https://truenas.local` or `https://nas.example.com`. If your NAS uses its own certificate, the app shows its fingerprint and asks you to trust it once.
2. **Sign in** with your TrueNAS username and password (and your two-factor code if you use 2FA). The app stays signed in for as long as you choose (1, 7 or 30 days).
3. **Optional extras** (System › Connection and System › Phone alerts): add your home address for faster connections at home, set up a VPN for access from anywhere, and choose which alerts reach your phone.

## Privacy

YTN talks only to the servers you add. There are no ads, analytics, tracking or accounts. Your passwords, keys and sessions stay encrypted on your phone. The only other requests are for app icons from TrueNAS's own catalog servers and a once-a-day check with GitHub for a newer version (you can turn it off). Neither sends any personal data.

> [!NOTE]
> **Found a problem?** If you run into a bug or something doesn't work as expected, please [open an issue](https://github.com/1immortal/ytn/issues) on this repo. It helps to include the app version (System › About), your Android version and what you were doing. **Please never post passwords, OTP (2FA) codes, API keys or your server addresses**, not even in screenshots or logs.

## More

- [Everything YTN can do](docs/FEATURES.md)
- [Technical details](docs/TECHNICAL.md): how it works, sign-in and sessions, supported TrueNAS versions, security notes and known limitations
- [Building from source](docs/BUILDING.md)

## About

Designed and built by Grok Bot. Thanks for trying it out!

YTN is an independent project, not affiliated with or endorsed by iXsystems. TrueNAS is a trademark of iXsystems, Inc.

## License

MIT. See [LICENSE](LICENSE).

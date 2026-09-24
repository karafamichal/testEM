# testEM Web (PWA)

The web version of the unofficial **testEM** client for `sadzv.qrbus.me`, mainly for iPhones. People open the site in Safari, tap **Share → Add to Home Screen**, and from then on it launches full screen like an app. No App Store needed.

A browser can't talk to `sadzv.qrbus.me` or `cp.sk` directly (CORS, cross-site cookies), so this is **one small Node server** that serves the app from `public/` and fetches from those sites on the browser's behalf, the same way the Android app does.

## Features (v2)

- **Ticket**: rotating QR code (error correction H), freshness bar, offline warning with the code's age, full-screen code, card switcher, low-credit and expiry banners, next buses from your favourite stop.
- **Departures**: stop search, favourites, nearby stops, live departure boards with delays, each trip's stop list. Works without signing in.
- **Follow a bus**: a live card with the same progress bar as the Android notification (the bus moves along, squares mark where you get on and off). Push alerts before the bus reaches your stop (how many minutes is up to you, under Reminders), before you get off, and on arrival, even with the app closed (iOS 16.4+ from the Home Screen).
- **Community delays** and **Will I catch it?**: the same opt-in features as Android, asked on first start and changeable under Account.
- **Planner**: cp.sk connections for Slovakia, Banská Bystrica or Zvolen, suggestions, swap, time, direct only, saved and recent routes, platforms, share, later connections, all stops of a bus, live departures at a stop.
- **History**: monthly spending chart, average fare, trips your credit covers, most-used stops, CSV export.
- **Account and settings**: card details, validity, several cards, reminders, optional PIN lock (your saved password is then encrypted with AES-GCM using a key derived from the PIN), lock timeout, colour themes, dark mode and pure black, Slovak and English.
- **Report a bug**: the app's recent log lines, with optional text, name and email, after the user agrees.

Not possible on the web: an ongoing lock-screen notification (iOS has no such thing for web apps; push alerts cover the key moments), biometric unlock, and screen brightness control.

## Support

Like the project? You can support further work at **[karafa.net/support](https://karafa.net/support/)** (also under **Account → Support testEM** in the app).

## Run it

Node 18.17+ (20+ recommended).

```bash
cd web
npm install
npm start          # http://localhost:3000
node test.mjs      # self-check of the shared logic and the cp.sk parser
```

| Variable | Default | Purpose |
| --- | --- | --- |
| `PORT` | `3000` | Port to listen on |
| `QRBUS_BASE_URL` | `https://sadzv.qrbus.me` | Upstream for sign-in and tickets |
| `HUB_URL` | *(empty)* | emhub base URL, e.g. `http://emhub-host:8080`. Empty turns off bug reports and community delays |
| `HUB_APP_KEY` | *(empty)* | App key for emhub. Stays on the server; the browser never sees it |
| `DATA_DIR` | `./data` | Where the push (VAPID) keys are created on first start. Keep it private and out of git |
| `VAPID_SUBJECT` | `mailto:noreply@karafa.network` | Contact for push services |

Serve it over HTTPS (a Cloudflare Tunnel works); service workers and push need it.

## How sessions work

- The browser stores `{ email, password }` in `localStorage`, encrypted when a PIN is set.
- `POST /api/login` signs in to qrbus. The server keeps the qrbus session cookie in memory and gives the browser an opaque `sessionId`. Sessions last 6 hours; a restart just forces a quiet re-login.
- Followed buses for push alerts are also kept in memory; the app registers again when it's opened.

## Disclaimer

Unofficial client for `sadzv.qrbus.me`, owned by EMtest. Not affiliated with, authorized or endorsed by EMtest. Use at your own risk.

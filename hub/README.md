# emhub

Backend and admin dashboard for testEM:

- **Bug reports** from the Android app and the web version: the report, optional name and email, and the app's logs, sent only after the user ticks the consent box.
- **Email**: new reports go to the admin inbox, the reporter gets a confirmation, and admins reply from the dashboard. All emails are HTML templates in `emails/` with `{{placeholders}}`.
- **Community delays** for intercity buses, which have no live position. Riders who opted in tap where the bus really is, and everyone following that bus sees the pooled delay.
- **Admin sign-in** with a local password, or through Authentik (OpenID Connect) once an admin has linked their Authentik account.

It's one Python file with the standard library only: no pip, no database server. Data lives in a single SQLite file.

## Run it

```bash
sudo useradd --system --home /var/lib/emhub --shell /usr/sbin/nologin emhub
sudo mkdir -p /opt/emhub /var/lib/emhub && sudo chown emhub:emhub /var/lib/emhub
sudo cp -r hub.py emails static /opt/emhub/
sudo cp hub.env.example /etc/emhub.env && sudo chmod 600 /etc/emhub.env   # then edit it
sudo cp emhub.service /etc/systemd/system/ && sudo systemctl enable --now emhub
```

Python 3.10+ (Debian 12 ships 3.11). It listens on `HUB_PORT` (8080). Put it behind a Cloudflare Tunnel or another HTTPS proxy, and set `HUB_PUBLIC_URL` to the public address; cookies are marked `Secure` when that URL is https.

The first start creates the admin from `HUB_BOOTSTRAP_USER` / `HUB_BOOTSTRAP_PASSWORD`. Change the password under **Settings** after signing in.

**More admins**: under **Settings → Admins**, enter a username and email and press **Send invitation**. They get an email with a one-time link (valid 72 hours) to choose their own password, then they're signed in. Until then the account can't be used. If the email can't be sent, the dashboard shows the link so you can pass it on yourself; **Resend** makes a new link and the old one stops working.

Self-check: `python3 test_hub.py`.

## Authentik

1. In Authentik, create an **OAuth2/OpenID Provider**: confidential client, redirect URI `https://<your emhub host>/auth/oidc/callback`, scopes `openid email profile`.
2. Create an **Application** that uses it, for example with the slug `emhub`.
3. In emhub, open **Settings → Authentik**. Enter the issuer `https://<authentik host>/application/o/emhub/`, the client ID and the client secret, save, and press **Check connection**. (The `HUB_OIDC_*` variables in `/etc/emhub.env` also work, as starting values.)
4. Sign in with your password, open **Settings → Authentik sign-in → Link**. From then on, "Sign in with Authentik" works for that user.

Only linked users can sign in through Authentik; an unknown Authentik account is turned away. Sign-in uses the authorization code flow with PKCE, and the user's identity comes from the userinfo endpoint.

Each admin can also turn on **Sign in with Authentik only** under Settings (only after linking). From then on their password is refused even when it's right, and their other password sessions are signed out. Unlinking is blocked while the switch is on. If Authentik is ever down, turn it off on the server:

```bash
cd /opt/emhub && sudo -u emhub env HUB_DB=/var/lib/emhub/hub.db python3 hub.py allow-password <username>
```

## Email

Set the SMTP server, sender and alert inbox under **Settings → Email** (the `HUB_SMTP_*` variables are only starting values). **Send test email** checks the route. Secrets like the SMTP password are never shown again after saving; leave the field blank to keep them.

Every template can be edited under **Settings → Edit email templates**, with a live preview filled with sample values. Edits are stored in the database; **Reset to default** brings back the file version. Templates:

| File | Sent when | Placeholders |
| --- | --- | --- |
| `admin_new_bug.html` | a report arrives | `bug_id`, `title`, `description_html`, `name`, `email`, `category`, `platform`, `app_version`, `device`, `logs_lines`, `created`, `dashboard_url` |
| `user_ack.html` | a report with an email arrives | as above |
| `user_reply.html` | an admin replies | as above plus `reply_html`, `agent`, `status_label` |
| `user_status.html` | an admin changes the status and ticks "email the reporter" | as above plus `status_label` |
| `invite.html` | an admin invites a new admin | `username`, `invited_by`, `invite_url`, `expires_hours`, `dashboard_url` |
| `test.html` | Settings → Send test email | `username`, `smtp`, `dashboard_url` |

`{{name}}` is HTML-escaped; `{{{name}}}` inserts HTML that was already made safe (the `*_html` values).

## API

Apps send `X-App-Key` (one of `HUB_APP_KEYS`). An app key ships inside the app, so treat it as an identifier rather than a secret; rate limits per IP and per rider do the real protecting.

| Method | Path | Body / query |
| --- | --- | --- |
| POST | `/api/v1/bugs` | `consent: true`, plus `description` and/or `logs`; optional `title`, `category`, `name`, `email`, `platform`, `appVersion`, `device` |
| POST | `/api/v1/community/reports` | `tripKey`, `line`, `stopIndex`, `stopName`, `scheduledMs`, `reporter` |
| GET | `/api/v1/community/delay?trip=<tripKey>` | → `{ delay: { delaySeconds, reporters, updatedAt, lastStopName } \| null }` |
| GET | `/api/v1/health` | no key needed |

`tripKey` is `line|first stop|first departure (epoch ms)`, the same bus on every phone. `reporter` is a random id the app creates for each followed bus, so reports can't be linked across trips. The server uses its own clock for "now", so a phone with the wrong time can't skew the delay.

How the delay is pooled: only the last 45 minutes count, each rider counts once (their latest report), newer reports weigh more (10-minute half-life), and with 3 or more riders any report more than 5 minutes from the median is ignored. Admins can delete reports or block a rider id from the Community page.

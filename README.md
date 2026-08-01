# OSRS TCG Online

A RuneLite plugin that turns Old School RuneScape into a card game backed by
[osrscardexchange.com](https://www.osrscardexchange.com).

This is the **first increment**: account linking. Before the plugin can do
anything on your behalf (open packs, sync a collection, trade), it needs to know
*whose* account it is acting for — so it authenticates against the exchange API
and links this install to your account. The card-game mechanics build on top of
that link and land in later increments.

## How linking works (device pairing)

The plugin never sees your password. It uses a device-pairing handshake, the
same shape as the OAuth 2.0 Device Authorization Grant (RFC 8628):

1. You click **Link my account** in the plugin's side panel.
2. The plugin asks the API to open a handshake and is given a short **code**
   (e.g. `ABCD-2345`) and a private device secret it keeps to itself.
3. The panel shows the code and an **Open link page** button. On that page you
   sign in to your exchange account and enter the code.
4. Confirming on the website is what actually authorises the link — an attacker
   who only glimpsed the code can't do it, because they'd need to be signed in as
   you. This mirrors the site's existing "confirm your RSN" step.
5. The plugin, which has been polling with its device secret, is handed a
   long-lived **plugin token** on the next poll and stores it. It's now linked.

The token is sent as `Authorization: Bearer octp_…` on future requests, is
stored only as a hash on the server, and can be revoked per device (the panel's
**Unlink** button, or a future "signed-in devices" list on your profile).

## Configuration

- **API base URL** — which exchange API to talk to. Resolution order, so a
  deployment can set it without editing code and a player can still override it:
  1. the **config field** if you set it explicitly (highest priority);
  2. the `THECARDEXCHANGE_API_URL` **environment variable**, or the
     `-Dthecardexchange.apiUrl` **system property**;
  3. the built-in default `http://localhost:3001` (the local standalone backend).

  Leave the config field blank to fall through to the env var / default.
- **Chat notifications** — announce linking status in the chat box.

## Building / running

From the **workspace root** there's a launch script (mirrors the other plugins'
`start-*.ps1`):

```powershell
.\start-thecardexchange-tcg-plugin.ps1
.\start-thecardexchange-tcg-plugin.ps1 -ApiUrl https://api.osrscardexchange.com
.\start-thecardexchange-tcg-plugin.ps1 -Log
```

`-ApiUrl` points the plugin at a specific exchange API for that run (it sets both
`THECARDEXCHANGE_API_URL` and Gradle `-PapiUrl`, which the build turns into
`-Dthecardexchange.apiUrl`).

Or directly, from this directory:

```powershell
.\gradlew.bat run --console=plain                     # boot RuneLite (dev mode) with the plugin
.\gradlew.bat run --console=plain -PapiUrl=https://…  # ...pointed at a specific API
.\gradlew.bat build                                   # compile
```

The `run` task side-loads the plugin via `ExternalPluginManager.loadBuiltin(...)`
then starts RuneLite; the client stops at the login screen, click **Play** to
enter the game.

To exercise the flow end to end you need the API running (see `../api`):

```bash
cd ../api && bun run index.ts   # serves the API and the /link verification page on :3001
```

## Server side

The pairing endpoints live in the standalone backend (`../api`):

- `POST /api/v1/plugin/link/start` — open a handshake (plugin)
- `POST /api/v1/plugin/link/poll` — poll for confirmation (plugin)
- `GET  /api/v1/plugin/session` — verify a stored token (plugin)
- `POST /api/v1/plugin/logout` — revoke this device's token (plugin)
- `POST /api/plugin/link/confirm` — confirm a code (website, signed in)
- `GET  /link` — the browser verification page

Unofficial and not affiliated with Jagex.

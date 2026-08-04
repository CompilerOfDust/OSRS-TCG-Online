# TCG Online (TheCardExchange)

A RuneLite plugin that turns Old School RuneScape into a card game, backed by
[osrscardexchange.com](https://www.osrscardexchange.com).

Open packs, build a collection of ~7,000 cards drawn from the game's items and
NPCs, and trade duplicates with other players in game. There is an optional
challenge mode where an item is only yours to use once you have pulled its card.

Everything is in this one plugin — the collection, pack opening, the item lock
and trading are one ruleset rather than four features, so no companion plugin is
needed.

## What it does

- **Packs.** A pack costs 1,000 credits and holds five cards. Credits are earned
  by playing — skill levels, quests, achievement diary tiers and boss kills all
  pay — so your balance moves while you are doing something else.
- **A collection view** over the inventory, with search, sorting and filters, and
  a detail view per card showing its gem tier, what it unlocks and how many
  copies you hold. Spare copies sell back for a quarter of face value; your last
  copy of a card can never be sold.
- **Trading in game.** Right-click another player → **Trade Cards**. Both sides
  put up duplicates only, both accept, and one copy of each offered card moves
  across in a single step. Any change to either side resets both accepts.
- **A network badge** beside players who are also running the plugin, so you can
  see who you can trade with. This can be turned off, which also stops others
  offering to you.

## Game modes

Chosen once per character, and it cannot be changed afterwards.

**Normal** — unrestricted play. Collect and trade alongside ordinary Old School
RuneScape.

**CardMan** — the community *Cardcore* challenge: an item is only yours to use
once you have pulled its card. Items you have no card for are greyed out and
cannot be worn, eaten or used. Picking things up, banking, dropping and all
skilling are always allowed, so you can play normally and earn your way into your
own gear.

CardMan requires:

- **an ironman account** — any of the six types; and
- **a completely fresh account** — every skill at 1, Hitpoints at 10. Train
  anything at all and CardMan is no longer available on that character.

> **Finish Tutorial Island before enabling the plugin.** The tutorial hands out
> items you have no cards for, so with the item lock already running it is
> awkward to complete, and the XP it awards counts against the fresh-account
> check.

Cards only trade between characters on the **same** mode, and a character that
has not chosen one cannot trade at all. Full rules:
[Game modes guide](https://www.osrscardexchange.com/guide/game-modes).

## Linking your account

The plugin never sees your password. It uses a device-pairing handshake, the same
shape as the OAuth 2.0 Device Authorization Grant (RFC 8628):

1. Click **Link account** in the plugin's side panel.
2. The plugin opens a handshake and is given a short code plus a private device
   secret it keeps to itself.
3. The panel shows the code and opens the website, where you sign in and confirm
   it.
4. Confirming is what authorises the link. Somebody who only glimpsed the code
   cannot use it, because they would also need to be signed in as you.
5. The plugin, polling with its device secret, is handed a plugin token and
   stores it.

The token is sent as `Authorization: Bearer octp_…`, is stored only as a hash on
the server, and can be revoked per device from the panel's **Unlink** button or
from your profile.

**Treat the pairing code like a password while it is on screen** — it is valid
for ten minutes.

You never type a RuneScape name anywhere. The plugin reports the character you
actually logged in as, which is also why a rename is picked up automatically.

## What leaves your client

- **Nothing at all until you link an account.**
- After linking: the character you are logged in as, its skill levels and XP,
  finished quests and completed diary tiers — the basis for the credits you earn
  — plus the cards you own and the trades you make.
- **Nothing about other players.** The list of who is online is downloaded whole
  and matched locally, so no third party's name is ever sent anywhere.
- Nothing is sent from seasonal, beta or tournament worlds.

## How it connects

The plugin talks to **[osrscardexchange.com](https://www.osrscardexchange.com)**,
which is the same project — this is its official plugin, not a third-party client
for somebody else's service.

- **Ordinary HTTPS** for everything with a request and an answer: the card
  catalogue, your collection, opening a pack, selling a spare, binding a
  character, the online list.
- **One WebSocket, for trading only.** A trade is not request-and-answer — the
  other player acts when they act, and both windows have to agree at every step,
  so the server pushes. The socket stays open while you play, with a keepalive
  ping, and carries nothing but trade events.

Which server it reaches is chosen from the **OSRS world you are logged into**, so
both players in a trade land on the same one. That is a correctness requirement
rather than a speed optimisation: a trade in progress lives on a single server,
and two players routed apart could not see each other's offers.

## Independence from other plugins

**This plugin needs no other plugin, and reads none.** It does not extend, wrap,
patch, read the state of, or depend on anything else installed — there is not a
single import from another plugin's package in the source. Uninstall everything
else and it behaves identically.

Everything is deliberately in this one plugin: the collection, pack opening, the
item lock and trading are one ruleset, and splitting them across plugins would
mean two of them disagreeing about what you have unlocked.

For that reason it will **warn** — once, on login — if it finds another enabled
plugin covering the same ground, because two item locks fighting over the same
menu entries reads as this plugin being broken. It only warns. Nothing is
disabled, nothing is changed: which plugins you run is your decision.

## Configuration

- **API base URL** — normally leave blank. The plugin picks the server matching
  the OSRS world you are logged into (`eu.` for UK/Germany worlds, `us.` for US
  and Australia), which is what puts both players in a trade on the same server.
  Setting this pins every request to one host and turns that off; it is for
  running your own copy.
- **Show me as online** — publishes your badge. Turning it off also stops others
  right-clicking you to offer a trade.
- Plus toggles for the two orbs, the credit balance, pack sounds and volume, the
  item lock, and each badge placement.

## Building

Requires JDK 11+.

```bash
./gradlew build          # compile and run tests
./gradlew run            # boot RuneLite in developer mode with the plugin loaded
./gradlew shadowJar      # a self-contained jar
```

`run` side-loads the plugin via `ExternalPluginManager.loadBuiltin(...)` and then
starts RuneLite; the client stops at the login screen — click **Play** to enter
the game.

By default `run` points at a local backend on `http://localhost:3001`, so a
development client never talks to the live service. Override it with
`-PapiUrl=https://…`, or pass `-Pprod` to run against the live deployment with
no override at all (which is the only way to exercise the world-to-region
routing).

## Licence

BSD 2-Clause. See [LICENSE](LICENSE).

Unofficial. Not affiliated with, endorsed by or connected to Jagex or RuneLite.

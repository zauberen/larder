# Larder

A self-hosted recipe, pantry, grocery list and meal plan app for one household.

One server holds a shared recipe collection, pantry, grocery list and meal plan. Signed-in members of the household manage them. Visitors can read published recipes and, if you allow it, check items off the grocery list. Claude can work with all four through a built-in MCP server.

## Features

- **Recipes.** Write them by hand or import them from a web page. Importing reads the page's schema.org recipe data, including its image. Recipes have tags, ratings, servings, times and a draft/published status. Only published recipes are shown to visitors.
- **Ingredients and units.** Each ingredient line is parsed into a quantity, unit, name and note, then matched to a shared ingredient list (seeded with common ingredients and their densities). Amounts convert within mass and within volume, and between the two when the ingredient has a density.
- **Pantry.** Logs what you have, either added by hand or from completed grocery items. Every recipe shows whether the pantry covers it: *Pantry ready*, *some*, or *none*, with a status for each line.
- **Grocery list.** Items are grouped by store. You can add a recipe's missing ingredients in one click, and amounts for the same ingredient are merged. When you check an item off, you confirm how much you bought and it goes into the pantry. You can keep the list private, open it to visitors, or share it through a secret link.
- **Made it.** Records that you cooked a recipe. It takes the scaled amounts out of the pantry, adds anything that ran out to the grocery list, and logs the cook.
- **Meal plan.** A week or month calendar. Drag recipes onto days, or tap a recipe and then a day. One button fills the grocery list with whatever the plan needs that the pantry and the list don't already cover.
- **MCP for Claude.** Claude can search recipes, import them, manage the pantry, grocery list and meal plan, and mark recipes as made. It signs in with an API key or OAuth: Larder is its own authorization server, supporting dynamic client registration and client ID metadata documents.
- **Accounts.** Admin and user roles, Argon2id password hashing and rate-limited logins. Admins manage users and site settings.

## Requirements

- Java 17 or newer (Jetty 12 needs it)
- The [Clojure CLI](https://clojure.org/guides/install_clojure)

## Running

```bash
clj -M:dev -m com.main
```

Open <http://localhost:8085>. On first start, Larder asks for an admin account, a site name, the port, a data folder and a database path. It saves your choices to `~/.larder`.

| Location | Default | What's there |
|---|---|---|
| `~/.larder` | | Settings (EDN) |
| `~/larder/` | data folder | Logs (`logs/`) and recipe images (`media/`) |
| `~/larder/larder.db` | | SQLite database |

To try it out without touching your real settings and data, point the JVM's home directory somewhere else:

```bash
clj -J-Duser.home=/tmp/larder-try -M:dev -m com.main
```

### Behind a reverse proxy

Larder serves plain HTTP, so put it behind a proxy (Caddy, nginx, …) that terminates TLS. Forward the `Host` or `X-Forwarded-Host` header: Larder checks the `Origin` of every write request against it. Then set **Settings → Site → Public base URL** to your public address. Share links and the OAuth metadata use that address.

Login rate limiting is keyed on the client address. Behind a proxy, every client appears to come from the proxy's address.

## Connecting Claude

Larder serves MCP at `/mcp` (Streamable HTTP).

**API key:** go to **Settings → Claude**, create a key and copy the command it shows:

```bash
claude mcp add --transport http larder https://your-larder.example/mcp \
  --header "Authorization: Bearer lk_..."
```

**OAuth:** add `https://your-larder.example/mcp` as a custom connector in Claude.ai or the desktop app, then sign in and approve the request. Those clients connect from Anthropic's cloud, so Larder must be reachable over public HTTPS.

Admins can turn either sign-in method off under **Settings → Site**. Each user can see and revoke their keys and connected apps under **Settings → Claude**.

## Development

```bash
clj -M:test                                   # run all tests (kaocha)
clj -M:test --focus com.larder.web-test       # run one namespace
clj -M:chk                                    # check for outdated dependencies
```

The stack is Clojure, Ring, Jetty, Compojure, Hiccup and htmx, with SQLite through `java.jdbc`. Pages are rendered on the server, and htmx swaps in the parts that change. [CLAUDE.md](CLAUDE.md) has a detailed architecture tour, covering routing, sessions, the ingredient parser, pantry status, the MCP and OAuth implementation, and the database schema.

The shell and styles come from [Block Shipper](https://github.com/zauberen/block-shipper).

### Roadmap

Done: shell, recipes, ingredients and units, web import, pantry, grocery list, pantry status, Made it, meal plan, MCP with API keys, MCP with OAuth.

Next: import from a Tandoor export.

## License

[GNU Affero General Public License v3.0](LICENSE). If you run a modified Larder as a network service, you must offer its users the source of your version.

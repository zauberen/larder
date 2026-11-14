# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What This Is

Larder is a self-hosted household recipe app in Clojure. One server holds one shared recipe list, pantry, grocery list and meal plan; signed-in users manage them, visitors read published recipes (and, per a site setting, check off grocery items), and Claude reaches all four over MCP. It copies the stack, shell and styles of [Block Shipper](https://github.com/zauberen/block-shipper) (files are copied, not shared).

It is built in phases (see the spec): **1 Shell** ✅ · 2 Recipes · 3 Ingredients and units · 4 Web import · 5 Pantry · 6 Grocery · 7 Pantry status · 8 Made it · 9 Meal plan · 10 MCP with API keys · 11 MCP with OAuth · 12 Tandoor import (waits for a real export). Update this file as each phase lands.

Decisions made so far: the app is named Larder (`com.larder`, `~/.larder`); Phase 3 seeds a common set of ingredients with densities via migration.

Every source file (Clojure, our JS/CSS; not `resources/public/js/vendor/`) starts with the AGPLv3 license header (`;; Larder - a household recipe, pantry and grocery list manager.`); add it to new files.

## Commands

```bash
# Run the application
clj -M:dev -m com.main

# Run all tests (kaocha, configured in tests.edn)
clj -M:test

# Run a single test namespace
clj -M:test --focus com.larder.web-test

# Check for outdated dependencies
clj -M:chk
```

To try the app without touching your real `~/.larder` and `~/larder`, point the JVM's home elsewhere: `clj -J-Duser.home=/some/tmp/dir -M:dev -m com.main` (paths starting with `~` expand from `user.home`).

## Architecture

**Entry point:** `src/com/main.clj` configures slf4j to log to `<data-root>/logs/YYYYMMDD-larder.log`, then loads and calls `com.larder/start-server`. Only `com.larder.settings` is required up front: slf4j reads its configuration when the first logger is created, and loading Jetty or `clojure.tools.logging` creates one. `start-server` pins the restart-only settings, decides whether setup is required, runs `on-startup` (create the data folder, migrate the DB) and starts Jetty.

**Nothing may touch the database at namespace load time.** Before setup there is no database (or the chosen one is elsewhere), and tests redefine the settings only inside their fixtures. E.g. the not-found route renders per request (`(route/not-found (fn [_] ...))`) because the 404 page reads the site name.

**Onboarding** (`pages/onboarding.clj`): when there is no `~/.larder`, no database file or no users, `start-server` only starts Jetty and sets `setup-required`; every request then goes to `setup-routes` (`/setup`, `POST /api/setup`, everything else redirects to `/setup`; static files still load). Setup asks for the admin account, site name, port, data folder and database path (plus advanced: upload piece size, debug), pins the chosen settings so that database is used at once, creates the data folder, writes the settings file, creates the admin, stores the site name in `global_settings`, runs the deferred startup and logs the admin in. Pages rendered before setup use `gs/default-site-name`, never the database.

**Routing** (`src/com/larder.clj`): Compojure routes with Ring middleware (keyword-params, params, static resources from `resources/public/`, cookies) plus `middleware.clj`: non-GET requests whose `Origin` doesn't match `Host`/`X-Forwarded-Host` get a 403, except `origin-exempt-paths` (`/mcp`, `/oauth/token`, `/oauth/register`, which authenticate by bearer token or client credentials); bodies over 1 MB (by Content-Length) get a 413 (upload pieces may be up to the piece size); security headers are added. Access checks live in `com.larder.auth`: `requires-user` and `requires-admin`. Handlers receive `(req user)`. Admin-only settings tabs return 404 to users; admin API endpoints return 403.

Routes so far: `/` public dashboard · `/login` `/logout` `/setup` · `/app` recipe list · `/app/recipes/new` Add · `/app/meal-plan` · `/app/pantry` · `/app/grocery` · `/app/settings/:tab` (`account`; `users` and `site` for admins) · `/api/...` htmx endpoints.

**Session management** (`api/session.clj`): manual sessions, not Ring's. On login a UUID sid is stored in the `sessions` table and sent as the `larder-session-id` cookie (HttpOnly, SameSite=Strict, `Secure` over https); sessions expire after `db/session-ttl-seconds` (30 days). The session only names the user; role and enabled flag are re-read from the DB on every request (`auth/current-user` → `users/by-username`, which only returns enabled users), so disabling or demoting someone applies at once. Removing a user deletes their sessions. Unauthenticated htmx requests get an `HX-Redirect` to `/login`. Failed logins are rate limited per address (`ratelimit.clj`: 10 per 5 minutes, then 429; keyed on `:remote-addr`, since `X-Forwarded-For` can be forged — behind a reverse proxy all clients share the proxy's address).

**Roles** (`users.clj`): `admin` and `user`. Both can do everything with recipes, pantry, grocery list and meal plan; only admins manage users (add, change role, reset password, disable/enable, remove) and site settings. You can't change your own role or disable/remove yourself, and the last enabled admin can't be demoted, disabled or removed.

**Settings** (`settings.clj`): `~/.larder` (`config-path`; EDN, read with `clojure.edn`) merged over defaults. Re-read on each `get-settings`, except `restart-keys` (port, data folder, DB path), which are pinned at startup by `pin-restart-settings!`; `pending-restart` lists saved values not yet in effect. Keys: `:listen-port` (8085, so Block Shipper's 8081 stays free), `:data-root` (`~/larder`; logs in `logs/`, later recipe images), `:sqlite-db-path` (`~/larder/larder.db`), `:upload-chunk-mb` (1), `:debug` (enables the admin-only `/api/echo`). `pages/server_config.clj` renders them as a form, shared by onboarding and Settings → Site → Server configuration (`POST /api/site/config`). Site settings editable from the UI live in `global_settings` (`global_settings.clj`): `site_name`, `public_base_url` (used later for share links and OAuth metadata).

**Uploads** (`uploads.clj`): copied from Block Shipper unchanged in behaviour; not wired to a route yet (Phase 2 uses it for recipe images). Files are sent in pieces of `:upload-chunk-mb`, claimed with `take!`, swept after an idle hour.

**UI rendering**: server-side Hiccup + htmx. `pages/layout.clj` is the app shell: header (site name, username, public dashboard, settings, log out) and sidebar (Add, then Recipes, Meal plan, Pantry, Grocery). Pages return `(layout/main section ...)` — SECTION is the sidebar key to highlight — and `layout/app-page` wraps it (htmx navigation requests get only `#main`). In-app links use `ui/nav-attrs` so only `#main` is swapped and the URL is pushed; `main.js` moves the sidebar highlight to `#main`'s `data-section` after each swap. `pages/public.clj` renders the public dashboard with header tabs (Recipes; Grocery list joins in Phase 6 when access is Open) beside Log in. `ui.clj` holds icons and form helpers (`field`, `select`, `alert`, `tabs`, `copy-field`). htmx is vendored in `resources/public/js/vendor/`; `main.js` has copy buttons, themed `hx-confirm`/`data-prompt` dialogs (`bsDialog`) and the sidebar highlight. Styles are Block Shipper's `theme.css`/`main.css`/`login.css` copied as is, with Larder additions at the end of `main.css`. Response helpers in `response.clj`: `h-respond` for Hiccup, `respond` for HTML strings, `respond-generic` for other content types.

**Passwords**: Argon2id via `buddy-hashers`, Base64-encoded before storage.

## DB Schema

All tables are single-tenant (no household or owner column). Times are epoch milliseconds. `migration.clj` holds idempotent statements that all run whenever `server-version` changes; add new ones at the end (`create table if not exists`, `alter table ... add column`). The SQLite connection enables `foreign_keys`.

`db_version`, `users` (role `admin`|`user`, `enabled`), `sessions` (`sid`, `session_obj`, `created_at`), `global_settings` (`key`, `value`).

## Testing

`tests/com/larder/test_util.clj`: `tu/with-temp-env` / `tu/temp-env-fixture` point the settings and database into a temp dir, create `admin`/`admin` and reset rate limits; `tu/request` builds ring requests (`:form`, `:sid`, `:headers`, `:remote-addr`). `web-test/login` creates a session for a username. `onboarding_test.clj` runs a fresh install against a real settings file in a temp dir.

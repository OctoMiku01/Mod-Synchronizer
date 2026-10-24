# Mod Synchronizer

Fabric mod for Minecraft 26.2 that keeps your mods folder in line with a shared mod list. Only the list is shared:
every player downloads the jars straight from their source, checked against the hashes in the list.

## Settings

The settings open from [Mod Menu](https://modrinth.com/mod/modmenu), or without it from the "Mod Sync" button at the
top left of the title screen. There you set where the shared list is and which mods this installation needs, log in to
GitHub or enter an access code, compare with the list right away, or export the mods folder as a list. They are kept in
`config/modsync.json`.

## How it works

1. At start, the shared list is compared with the mods folder in the background.
2. On the title screen, the differences are shown: `+` to install, `-` to remove, `!` skipped.
3. The jars are downloaded to `.modsync/staging` and checked against their hashes.
4. A small helper process waits for the game to exit, then swaps the jars in (a jar in use cannot be replaced, on
   Windows not even deleted). Its log is `.modsync/apply.log`.

Only jars this mod installed are ever replaced or removed (`.modsync/state.json`); your own mods stay untouched.

"Exceptions…" in the settings keeps mods out of the sync on this computer, by mod ID or file name, like a mod you have
your own version of: their list entries are neither downloaded nor shown as differing, and their jars are never
replaced or removed. This is kept in `config/modsync.json` and does not change the shared list.

"Export mod list" in the settings writes the current mods folder as a list to `modsync-export.json` in the game folder: jars Modrinth knows get their
Modrinth entry, jars whose `fabric.mod.json` links a GitHub repo (`contact.sources`, `homepage` or `issues`) that has
them as a release asset get a GitHub entry, the rest an entry with source `file` to fill in by hand. A fork whose
contact links rightly point to the original can name its own repo in its `fabric.mod.json`:

```json
"custom": { "modsync": { "github": "owner/repo" } }
```

## GitHub

The shared list can be in a repo, private ones too: `github:owner/repo/path/to/modpack.json`, optionally with
`@branch` at the end. GitHub entries name the repo as `project`, the release tag as `version` and the asset as `file`.

When GitHub wants a login (private repo, or the anonymous rate limit is used up), the game shows a code to enter on
github.com/login/device; the settings log in and out by hand. The token is kept in
`~/.modsync/github-token`, shared by all instances.

Players without a GitHub account enter an access code in the settings instead: a fine-grained personal access token
of the list's owner with only "Contents: Read-only" on the repos the list needs. The settings check it with GitHub
before keeping it in the same place. Everyone who has the code reads those repos as its owner, so it is best given an
expiry date and replaced if it gets passed on.

The login goes through the GitHub App "Mod Synchronizer" (client ID `GITHUB_CLIENT_ID` in `ModSync.java`): device
flow on, only read access to repo contents, and only for the repos it is installed in, so a private repo has to be
added to its installation before players can read it. Players still need read access to the repo themselves. Another
GitHub App or OAuth app can be entered by its client ID in the settings.

## The list

```json
{
  "minecraft": "26.2",
  "loader": "fabric",
  "mods": [
    {
      "source": "modrinth",
      "project": "P7dR8mSH",
      "version": "ewUK83HI",
      "file": "fabric-api-0.161.0+26.2.jar",
      "hashes": { "sha1": "…", "sha512": "…" },
      "side": "both",
      "modId": "fabric-api"
    }
  ]
}
```

`side` is `client`, `server` or `both` (the default). `modId` is the Fabric mod ID in the jar (optional, the export fills it
in): other versions of the same mod in the mods folder are replaced, as Fabric does not start with two of them, even
when this mod did not install them (an older version, or one a mod's own updater put there). Without `modId` they are
found after the download.

## Sources

| Source | State |
|---|---|
| Modrinth | done |
| GitHub release assets (incl. private repos, device flow login) | done |
| CurseForge (API key, mods that forbid third-party downloads) | planned |

## Layout

- `core`: no Minecraft classes: list, hashes, sources, sync plan, download, helper process
- `client`: Fabric entrypoint, settings and sync screens

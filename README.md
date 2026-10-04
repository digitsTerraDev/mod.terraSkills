# TerraSkills

TerraSkills is a NeoForge 1.21.1 integration mod for TerraFirmaCraft's nutrition
system and Pufferfish's Skills.

## Research queue

TerraSkills treats each Pufferfish node as timed research. Its Pufferfish `cost` is the number of research points required; balanced TFC nutrition supplies the base point rate and each node's two configured RPG-stat affinities modify that rate. Only the first queue item progresses, and moving an item preserves its fractional progress.

Press `R` (configurable under TerraSkills controls) to open the custom research screen. TerraSkills clears points awarded by older TerraSkills versions on login. `/ts research list`, `/ts research move <from> <to>`, and `/ts research cancel <index>` manage the queue.

## Development setup

- Java 21
- Minecraft 1.21.1
- NeoForge 21.1.248
- TerraLib 0.1.0
- TerraFirmaCraft 4.2.9
- Pufferfish's Skills 0.18.3

Import the Gradle project in IntelliJ IDEA, then use the generated `client` or
`server` run configuration. From a terminal:

```powershell
.\gradlew.bat build
.\gradlew.bat runClient
```

TerraSkills downloads the versioned TerraLib JAR from its GitHub Release by
default. For local TerraLib development, publish its Maven artifact locally and
override the repository with `-Pterralib_repo=<path-to-terralib-repo>`.

The build also downloads TFC, Pufferfish's Skills, and the required Patchouli
runtime dependency from their configured Maven repositories.

## Integration entry point

Use `NutritionSkillsBridge` from server-side gameplay code. It currently exposes:

- `getAverageNutrition(player)` — TFC's normalized `[0, 1]` average.
- `getAvailablePoints(player, categoryId)` — unspent Pufferfish skill points.
- `addNutritionPoints(player, categoryId, amount)` — awards points using the
  separately tracked `terraskills:nutrition` source.

## Passive progression

The server config is created per world at `serverconfig/terraskills-server.toml`.
Point generation uses wall-clock time and follows this formula:

```text
(base points/day + sum(nutrient fullness ^ fullness exponent * nutrient rate)
 * balance multiplier) * RPG stat multiplier
```

Balance is based on the average pairwise closeness of all nutrients. Its configurable
minimum and exponent control how strongly an unbalanced diet is penalized. RPG
stats are `might`, `finesse`, `endurance`, `intelligence`, and `instinct`. Every
configured tree names a primary and secondary stat with separate configurable
bonuses. Fractional progress is saved independently for every tree.

Useful commands:

```text
/terraskills tree list
/terraskills tree select <category_id>
/terraskills info
/terraskills next
/terraskills stat set <players> <stat> <value>  # permission level 2
```

Java integrations can grant permanent base stats through `RpgStatsApi`. TerraSkills
also registers synchronized player attributes for temporary bonuses and origin powers:

```text
terraskills:might
terraskills:finesse
terraskills:endurance
terraskills:intelligence
terraskills:instinct
```

Neo Origins can grant these with its normal attribute modifier power. For example:

```json
{
  "type": "neoorigins:attribute_modifier",
  "attribute": "terraskills:might",
  "amount": 3.0,
  "operation": "add_value",
  "name": "Powerful Build",
  "description": "+3 Might"
}
```

The modifier belongs to the power, so Neo Origins removes the bonus automatically
when the power or origin is removed. TerraSkills does not require Neo Origins to load.

`generation.realMinutesPerSkillDay` controls the real-time duration of a skill
day (default `1440`, or 24 hours). `generation.enabled` is the startup master
switch. Runtime control is available without restarting:

```text
/ts generation status
/ts generation pause
/ts generation resume
```

`/ts` is a complete short alias for `/terraskills`; all other subcommands work
under either prefix. Paused time does not accumulate and is not paid out after
resuming.

`tree list` shows each tree's available Pufferfish points, saved fractional
progress, current rate, and estimated time to its next point. Inactive trees
retain their progress indefinitely. `/ts next` gives the same countdown for the
currently active tree.

## Releases and Packwiz

Every push and pull request is built by GitHub Actions. Push a version tag to
also create a GitHub Release containing the versioned mod JAR and `SHA256SUMS`:

```powershell
git tag v0.2.0
git push origin v0.2.0
```

The resulting Packwiz-compatible download URL is stable and version-specific:

```text
https://github.com/digitsTerraDev/mod.terraSkills/releases/download/v0.2.0/terraskills-0.2.0.jar
```

In the Packwiz repository, add the GitHub project with Packwiz's GitHub provider.
The regex selects the mod JAR instead of the checksum asset:

```powershell
packwiz github add digitsTerraDev/mod.terraSkills --regex '^terraskills-[0-9].*\.jar$'
```

Once a newer tagged release exists, update it normally through Packwiz:

```powershell
packwiz update terraskills
```

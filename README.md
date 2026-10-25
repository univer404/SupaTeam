# SupaTeam

SupaTeam is a Paper/Purpur team plugin with YAML persistence, team invites, homes, team chat, tab completion, a member information GUI, and optional PlaceholderAPI support.

## Requirements
- Paper or Purpur 1.21.11 (Java 21)
- PlaceholderAPI is optional

## Commands
- `/team` — open your team's member GUI
- `/team create <name>`
- `/team invite <player>`
- `/team join <name>` — requires a valid invite
- `/team leave`
- `/team info [name]`
- `/team sethome`, `/team delhome`, `/team home`
- `/team kick <player>`
- `/team chat` — toggle team-only chat; `/team chat <message>` sends one message
- `/team disband`, then `/team disband confirm`

The command alias `/teams` is supported. Settings are in `plugins/SupaTeam/config.yml`; saved team data is in `plugins/SupaTeam/teams.yml`.

## Placeholders
When PlaceholderAPI is installed:
- `%team_name%`
- `%team_inteam%`
- `%team_members%`

## Build
Use Java 21 and Maven: `mvn clean package`. GitHub Actions automatically builds the JAR and uploads it as the **SupaTeam** artifact on each push.

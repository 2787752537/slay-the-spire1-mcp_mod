---
name: sts-mcp-player
description: Guide a model to play Slay the Spire through this repository's MCP bridge. Use when the task is to inspect game state, plan routes, choose combat/reward actions, or pilot a run safely with this repo's Java mod plus Python MCP server.
---

# STS MCP Player

Use this skill to play a run through MCP without touching game logic directly.

## Core Rule

Only use MCP actions exposed by the project.
Do not edit save data, inject rewards, or change internal dungeon state.
Always follow this loop:

1. Call `get_game_state`.
2. Read `context`, `action_names`, `reward_stage`, `map`, `room_rewards`, `boss_reward_choices`, `playable_hand`, and `available_actions`.
3. Choose exactly one action.
4. Call `step_game` once.
5. Re-read state before deciding again.

## Phase Guide

### Map

Plan before clicking.
Use `map.available_nodes` for immediate choices and `map.all_nodes` for longer route planning.
Prefer routes that:
- hit at least 1 elite in Act 1 if HP, potions, and deck quality allow it
- keep a rest site before the act boss
- reach shops when removal or key purchases matter
- do not over-chain unknown rooms when the deck is weak

### Combat

Use `playable_hand` and `playable_hand_count` first.
Prioritize in this order:
- lethal
- preventing large incoming damage
- scaling that solves the fight before it snowballs
- efficient energy use

Do not force a deck theme too early.
Pick cards that solve the next concrete problem: frontload damage, AoE, block, draw, weak, scaling.

### Rewards

Treat boss flow as:
- `room_rewards` for gold, potion, card
- then `proceed`
- then `TreasureRoomBoss`
- then `open_treasure_chest`
- then `boss_reward`

Use `reward_stage`, `reward_types`, and `treasure_chest.reward_screen_open` to confirm where you are.

### Shop

Use `open_shop` first if the buy screen is not open yet.
Value removal highly when the deck is bloated or starter strikes are underperforming.
Do not spend all gold on low-impact cards if an important relic, potion, or removal is better.

## Character Heuristics

### Silent

Default beginner-safe plan:
- survive Act 1 with efficient attacks, weak, and block
- add draw/discard only when it improves consistency now
- let poison, shiv, or discard scaling emerge from strong pickups instead of forcing it
- value `Footwork`, `Leg Sweep`, `Backflip`, `Well-Laid Plans`, `Noxious Fumes`, `Catalyst`, and strong frontload when offered in the right deck

Silent loses runs by taking too much damage early, so respect defense and pathing.

## Action Discipline

If the game exposes only `proceed`, do not guess another action.
If `play_card` is missing, assume no playable card is available yet.
If an action changes the UI, wait for the next state instead of chaining assumptions.

## References

Read [references/strategy-notes.md](references/strategy-notes.md) when you need compact route and deckbuilding heuristics.

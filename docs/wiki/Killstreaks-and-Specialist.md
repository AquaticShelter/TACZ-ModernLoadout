# Killstreaks and Specialist

A class picks **either** killstreak items **or** Specialist. They are mutually exclusive.

## Killstreaks

You equip streak rewards in the class (the KILLSTREAK card). When your kill count in one life reaches a reward's
requirement, the item is given to you. Dying resets the count.

- **Tracker HUD**: a small panel at the bottom-right with the rewards and your progress. Position, size, opacity and colour are in
  the client config.
- **Banners**: when you or someone else earns a streak, a banner appears (who is shown, where, and for how long is configurable).
- **Killchain** keeps your chain through one death, but only while there is still a streak item you have not earned.
  When all of them are earned there is nothing left to save, so the death resets the chain straight away.
- **Hardline** makes every item cost one kill fewer.
- **Streak items do not feed the streak.** A kill made with a streak reward, or by what it spawns (a rocket, a drone, a
  grenade, a bullet from a streak gun), does not add to your count, for players and mobs alike. It still counts after you
  swap weapons, after the streak gun has run dry, and when a drone or missile lands the kill later. Quick Fix, Scavenger and
  XP are not affected by this rule.

### The radial (hold X)

Hold <kbd>X</kbd> (rebindable) to open a ring with the streaks your class has equipped. Move toward one and release to use it:

- A lit slot is an item you have right now. A dim slot has not been earned yet.
- The slot at the bottom, marked with an X, puts the weapon you were holding back in hand.
- A TACZ gun is just taken in hand.
- Any other item is taken in hand and **right-clicked for you**, because streak items come from many mods and each one
  activates on use. Set `radialAutoUse` to false if you would rather click yourself, and `radialUseHoldTicks` for items
  you have to hold to charge.
- When the item is spent you go back to the slot you came from. Items listed in `radialNoReturn` are left alone.

You can keep walking while the ring is open.

## Specialist

Turn Specialist on in the class and it replaces the streak items:

| Kills | What you get |
|---|---|
| 2 | The perk you picked for the first stage |
| 4 | The perk you picked for the second stage |
| 6 | The perk you picked for the third stage |
| 8 | Every available perk |

Rules:

- Each stage's list shows every enabled perk you have not already equipped or picked. The perk that only exists in one
  stage (Shield Up in the third) is listed first there. The list scrolls.
- **Killchain, Hardline, Overkill and Underkill never count for Specialist.** They cannot be picked, and the 8-kill unlock
  skips them. While Specialist is on, Killchain and Hardline are locked in the class.
- The 8 KILL tab is a read-only preview of what that unlock gives.
- Server owners can switch to a hand-picked list with `specialistPoolAll = false` (see the [admin guide](Admin-Guide.md)).

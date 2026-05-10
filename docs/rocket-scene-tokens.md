# Rocket Scene Tokens

## Source Of Truth

These values are the current shared rocket/flame alignment baseline for Compose dashboard and landing hero preview.

```text
rocketWidth: 132
rocketHeight: 228
flameWidth: 22
flameHeight: 75
flameY: 176
leftFlameX: -11
rightFlameX: 11
rocketHorizontalBleed: 12
rocketVerticalBleed: 28
```

## Derived Landing CSS

```text
sceneWidth = rocketWidth + rocketHorizontalBleed * 2 = 156
rocketLeft = rocketHorizontalBleed = 12
rocketTop = rocketVerticalBleed = 28
rocketCenterX = rocketLeft + rocketWidth / 2 = 78
leftFlameLeft = rocketCenterX + leftFlameX - flameWidth / 2 = 56
rightFlameLeft = rocketCenterX + rightFlameX - flameWidth / 2 = 78
flameTop = rocketTop + flameY = 204
```

## Rules

- Flame is rendered before the rocket so the engine caps mask the flame top edge.
- Flame transform origin is top center.
- Disconnect animation moves rocket and flame as one scene, not as separate layers.
- Debug overlay in desktop preview must stay available for pixel tuning.
- Any future visual tuning changes this file, Compose defaults, and landing CSS in the same commit.

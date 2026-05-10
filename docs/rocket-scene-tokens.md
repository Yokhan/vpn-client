# Rocket Scene Tokens

## Source Of Truth

These values are the current shared rocket/flame alignment baseline for Compose dashboard and landing hero preview.

```text
rocketWidth: 132
rocketHeight: 228
flameWidth: 22
flameHeight: 75
rocketY: -28
flameY: 177
leftFlameX: -10
rightFlameX: 8
rocketHorizontalBleed: 12
rocketVerticalBleed: 28
```

## Derived Landing CSS

```text
sceneWidth = rocketWidth + rocketHorizontalBleed * 2 = 156
sceneHeight = rocketHeight + rocketVerticalBleed * 2 = 284
rocketLeft = rocketHorizontalBleed = 12
rocketTop = rocketVerticalBleed + rocketY = 0
rocketCenterX = rocketLeft + rocketWidth / 2 = 78
leftFlameLeft = rocketCenterX + leftFlameX - flameWidth / 2 = 57
rightFlameLeft = rocketCenterX + rightFlameX - flameWidth / 2 = 75
flameTop = rocketTop + flameY = 177
```

## Rules

- Flame is rendered after the rocket so it visually sits above the lower engine area.
- Flame transform origin is top center.
- Rocket Y moves the rocket and flame together; Flame Y moves only the flame relative to the rocket scene.
- Rocket scene height is anchored to rocket height and vertical bleed; flame Y changes must not recenter the rocket.
- Disconnect animation moves rocket and flame as one scene, not as separate layers.
- Debug overlay in desktop preview must stay available for pixel tuning.
- Any future visual tuning changes this file, Compose defaults, and landing CSS in the same commit.

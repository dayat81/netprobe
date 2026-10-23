# Ookla Speedtest UI — REA notes (static)

**Artifact:** `ookla-speedtest.apk` (`org.zwanoo.android.speedtest`)  
**SHA-256:** `1a2d9e32060b951f9c5f2b06d51294ccd6dd496e4b250dee3741003adc17c667`  
**Method:** apktool decode on azvm (`/tmp/ookla-ui-decode`, `/tmp/ookla-ui-smali`). JADX MCP (`REA_JADX_MCP_JAR`) was not configured in this session.

## Gauge stack (observations)

| Piece | Class / resource |
|-------|------------------|
| Arc view | `com.ookla.mobile4.views.gauge.Gauge` |
| Interval ticks | `…gauge.e` (`IntervalsGaugeDrawingDelegate`) |
| Needle | `…gauge.g` (`NeedleGaugeDrawingDelegate`) |
| Markers | `…gauge.d` (`GaugeMarkersDrawingDelegate`) |
| Center speed | `com.ookla.mobile4.views.SpeedDisplay` + `ookla_speeddisplay_layout.xml` |
| GO | `com.ookla.mobile4.views.go.GoButton` |
| Host layout | `fragment_internet.xml` (`@id/gauge`, `@id/go_button`, SpeedDisplay DL/UL) |

## Geometry (from `Gauge.smali`)

- Track/fill arc: **start 135°**, **sweep 270°** (`o0=0x87`, `p0=0x10e`, `l0=270f`).
- Fill driven by `setTargetFillPercentage` → `mainArcSweepAngle` (animated).
- Fill uses `SweepGradient` (+ `ComposeShader` / radial glow helpers).
- Default interval count field `v=9` (9 labels).

## Mbps scale (equal angle slots, non-linear Mbps)

From `res/values/arrays.xml` → `gauge_scale_1000_megabits`:

`0, 5, 10, 50, 100, 250, 500, 750, 1000`

Other arrays exist for 100/500 Mbps and byte-based scales.

## Palette (download multi)

- `ookla_ui_blue` `#01A3E5`
- `ookla_ui_green` `#19BF78`
- Upload multi: purple `#6B3295` → pink `#E97BCE`
- Chrome: `ookla_black_blue` `#0B0C1B`, `ookla_dark_navy` `#26263B`, grey `#9193A8`

## MySignal mapping

`SpeedtestGaugeView` (2.5.40+) adopts 270° geometry, the 1000 Mbps interval array, and DL/UL gradients — not a pixel clone of Ookla assets/fonts.

//! PORT02 — small pure-logic ports from `utils/Utils.kt`: the
//! tab-strip color math. Bit-level parity with the Kotlin originals,
//! signed-shifts and float-truncation included.

/// `Utils.isColorTooDark` — luminance heuristic used to decide
/// whether a page-color favicon/tab background needs dimming.
#[uniffi::export]
pub fn is_color_too_dark(color: i32) -> bool {
    let r = ((((color >> 16) & 0xff) as f32) * 0.3) as i32 & 0xff;
    let g = ((((color >> 8) & 0xff) as f32) * 0.59) as i32 & 0xff;
    let b = (((color & 0xff) as f32) * 0.11) as i32 & 0xff;
    let gr = (r + g + b) & 0xff;
    let gray = gr + (gr << 8) + (gr << 16);
    gray < 0x727272
}

/// `Utils.mixTwoColors` — linear blend of two ARGB colors by
/// [amount] of `color1`.
#[uniffi::export]
pub fn mix_two_colors(color1: i32, color2: i32, amount: f32) -> i32 {
    let inverse = 1.0 - amount;
    let r = ((((color1 >> 16) & 0xff) as f32) * amount
        + (((color2 >> 16) & 0xff) as f32) * inverse) as i32
        & 0xff;
    let g = ((((color1 >> 8) & 0xff) as f32) * amount
        + (((color2 >> 8) & 0xff) as f32) * inverse) as i32
        & 0xff;
    let b = (((color1 & 0xff) as f32) * amount
        + ((color2 & 0xff) as f32) * inverse) as i32
        & 0xff;
    (0xff_i32 << 24) | (r << 16) | (g << 8) | b
}

/// `DrawableUtils.mixColor` — fraction-lerp between two ARGB colors,
/// alpha channel included (unlike [`mix_two_colors`]).
#[uniffi::export]
pub fn mix_color_fraction(fraction: f32, start_value: i32, end_value: i32) -> i32 {
    let sa = (start_value >> 24) & 0xff;
    let sr = (start_value >> 16) & 0xff;
    let sg = (start_value >> 8) & 0xff;
    let sb = start_value & 0xff;
    let ea = (end_value >> 24) & 0xff;
    let er = (end_value >> 16) & 0xff;
    let eg = (end_value >> 8) & 0xff;
    let eb = end_value & 0xff;
    (sa + (fraction * (ea - sa) as f32) as i32) << 24
        | (sr + (fraction * (er - sr) as f32) as i32) << 16
        | (sg + (fraction * (eg - sg) as f32) as i32) << 8
        | (sb + (fraction * (eb - sb) as f32) as i32)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn dark_detection() {
        assert!(is_color_too_dark(0xff101010u32 as i32));
        assert!(!is_color_too_dark(0xffffffffu32 as i32));
        // 0x80 gray blends to 0x7f7f7f — just over the 0x727272 gate.
        assert!(!is_color_too_dark(0xff808080u32 as i32));
    }

    #[test]
    fn mixing() {
        // 0 amount keeps color2; 1 keeps color1.
        assert_eq!(mix_two_colors(0xff000000u32 as i32, -1, 0.0), -1);
        assert_eq!(mix_two_colors(-1, 0xff000000u32 as i32, 1.0), -1);
        // Black + white at 0.5 → ~0x7f gray (float truncation parity).
        assert_eq!(mix_two_colors(-1, 0xff000000u32 as i32, 0.5) as u32, 0xff7f7f7f);
        // Channel-level check: red at 0.25 over black.
        let mixed = mix_two_colors(0xffff0000u32 as i32, 0xff000000u32 as i32, 0.25);
        assert_eq!(mixed as u32 & 0xff0000, 0x3f0000);
    }

    #[test]
    fn fraction_mixing() {
        assert_eq!(mix_color_fraction(0.0, 0x11223344, 0x55667788), 0x11223344);
        assert_eq!(mix_color_fraction(1.0, 0x11223344, 0x55667788), 0x55667788);
        // Midpoint truncates like the Java `(int)` cast: 0x11 +
        // (0x55-0x11)*0.5 = 0x11 + 0x22 = 0x33 per channel.
        assert_eq!(mix_color_fraction(0.5, 0x11223344, 0x55667788), 0x33445566);
    }
}

pub const OPAQUE_MARKER: &str = ".wh..wh..opq";

pub fn marker_name(name: &str) -> String {
    format!(".wh.{}", name)
}

pub fn is_whiteout_marker(name: &str) -> Option<String> {
    if name.starts_with(".wh.") && name != OPAQUE_MARKER {
        Some(name.trim_start_matches(".wh.").to_string())
    } else {
        None
    }
}

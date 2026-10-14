//! Regression for R-2 (security test 2026-10-14): Cargo applies profiles only from
//! the workspace root; the release profile in this crate's manifest was ignored,
//! so the shipped library had no LTO and unwound panics across the JNI boundary.

const WORKSPACE: &str = include_str!("../../Cargo.toml");
const CRATE: &str = include_str!("../Cargo.toml");

#[test]
fn release_profile_is_at_the_workspace_root_with_panic_abort() {
    let profile = WORKSPACE
        .split("[profile.release]")
        .nth(1)
        .expect("rust/Cargo.toml has no [profile.release]");
    let profile = profile.split("\n[").next().unwrap_or(profile);
    let compact: String = profile.chars().filter(|c| !c.is_whitespace()).collect();
    assert!(compact.contains("panic=\"abort\""), "panic = \"abort\" missing");
    assert!(compact.contains("lto=true"), "lto = true missing");
}

#[test]
fn member_manifest_declares_no_profile() {
    assert!(!CRATE.contains("[profile."), "profiles in a member manifest are ignored by Cargo");
}

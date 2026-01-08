use anyhow::Result;

fn main() -> Result<()> {
    let doc = spaces_daemon::api::openapi::openapi();
    let json = serde_json::to_string_pretty(&doc)?;
    println!("{}", json);
    Ok(())
}

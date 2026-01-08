#[tokio::main]
async fn main() -> anyhow::Result<()> {
    spaces_daemon::run().await
}

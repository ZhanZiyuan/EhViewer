//! Test-only process boundary for the local Kotlin release API model. No network beyond loopback.
use std::env;
use std::io::{self, Read, Write};
use std::net::{SocketAddr, TcpStream};
use std::time::Duration;

const LIMIT: usize = 1024 * 1024;

fn invalid(message: &str) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, message)
}

fn write_field(stream: &mut TcpStream, bytes: &[u8]) -> io::Result<()> {
    if bytes.len() > LIMIT {
        return Err(invalid("Offline request exceeds the field limit"));
    }
    stream.write_all(&(bytes.len() as u32).to_be_bytes())?;
    stream.write_all(bytes)
}

fn read_field(stream: &mut TcpStream) -> io::Result<Vec<u8>> {
    let mut length = [0; 4];
    stream.read_exact(&mut length)?;
    let size = u32::from_be_bytes(length) as usize;
    if size > LIMIT {
        return Err(invalid("Offline response exceeds the field limit"));
    }
    let mut bytes = vec![0; size];
    stream.read_exact(&mut bytes)?;
    Ok(bytes)
}

fn run() -> Result<i32, Box<dyn std::error::Error>> {
    let mut arguments = env::args().skip(1);
    let mode = arguments
        .next()
        .ok_or_else(|| invalid("Missing client mode"))?;
    if !matches!(mode.as_str(), "gh" | "checksum") {
        return Err(invalid("Unknown offline mode").into());
    }
    let arguments: Vec<_> = arguments.collect();
    if arguments.len() > 128 {
        return Err(invalid("Too many offline arguments").into());
    }
    let endpoint: SocketAddr = env::var("MOCK_RELEASE_ENDPOINT")?.parse()?;
    if !endpoint.ip().is_loopback() {
        return Err(invalid("Offline mock endpoint must be loopback").into());
    }
    let mut stream = TcpStream::connect_timeout(&endpoint, Duration::from_secs(5))?;
    stream.set_nodelay(true)?;
    stream.set_read_timeout(Some(Duration::from_secs(5)))?;
    stream.set_write_timeout(Some(Duration::from_secs(5)))?;
    write_field(&mut stream, env::var("MOCK_RELEASE_TOKEN")?.as_bytes())?;
    write_field(&mut stream, mode.as_bytes())?;
    stream.write_all(&(arguments.len() as u32).to_be_bytes())?;
    for argument in arguments {
        write_field(&mut stream, argument.as_bytes())?;
    }
    let mut input = Vec::new();
    if mode == "checksum" {
        io::stdin().take(LIMIT as u64 + 1).read_to_end(&mut input)?;
    }
    write_field(&mut stream, &input)?;
    let mut code = [0; 4];
    stream.read_exact(&mut code)?;
    let code = i32::from_be_bytes(code);
    if !(0..=255).contains(&code) {
        return Err(invalid("Invalid offline exit status").into());
    }
    io::stdout().write_all(&read_field(&mut stream)?)?;
    io::stdout().flush()?;
    io::stderr().write_all(&read_field(&mut stream)?)?;
    io::stderr().flush()?;
    Ok(code)
}

fn main() {
    match run() {
        Ok(code) => std::process::exit(code),
        Err(error) => {
            eprintln!("Offline test client failed: {error}");
            std::process::exit(70);
        }
    }
}

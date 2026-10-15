//! Minimal stub of the `serial` 0.4 API surface used by portable-pty's
//! serial tty backend. All constructors fail: serial ports are unsupported
//! on this target, and rust-pty never opens them (native pty only).
use std::ffi::OsStr;
use std::io::{Read, Result, Write};
use std::time::Duration;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BaudRate {
    Baud9600,
    Baud19200,
    Baud38400,
    Baud57600,
    Baud115200,
    Other(usize),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CharSize {
    Bits5,
    Bits6,
    Bits7,
    Bits8,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Parity {
    ParityNone,
    ParityOdd,
    ParityEven,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum StopBits {
    Stop1,
    Stop2,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FlowControl {
    FlowNone,
    FlowSoftware,
    FlowHardware,
}

#[derive(Debug, Clone)]
pub struct PortSettings {
    pub baud_rate: BaudRate,
    pub char_size: CharSize,
    pub parity: Parity,
    pub stop_bits: StopBits,
    pub flow_control: FlowControl,
}

#[derive(Debug)]
pub struct SystemPort;

pub fn open<T: AsRef<OsStr> + ?Sized>(port: &T) -> Result<SystemPort> {
    Err(std::io::Error::new(
        std::io::ErrorKind::Unsupported,
        format!("serial ports unsupported: {:?}", port.as_ref()),
    ))
}

pub trait SerialPort: Read + Write {
    fn configure(&mut self, settings: &PortSettings) -> Result<()>;
    fn set_timeout(&mut self, timeout: Duration) -> Result<()>;
    fn read_cd(&mut self) -> Result<bool>;
}

impl Read for SystemPort {
    fn read(&mut self, _buf: &mut [u8]) -> Result<usize> {
        unimplemented!("serial ports unsupported")
    }
}

impl Write for SystemPort {
    fn write(&mut self, _buf: &[u8]) -> Result<usize> {
        unimplemented!("serial ports unsupported")
    }
    fn flush(&mut self) -> Result<()> {
        unimplemented!("serial ports unsupported")
    }
}

impl SerialPort for SystemPort {
    fn configure(&mut self, _settings: &PortSettings) -> Result<()> {
        unimplemented!("serial ports unsupported")
    }
    fn set_timeout(&mut self, _timeout: Duration) -> Result<()> {
        unimplemented!("serial ports unsupported")
    }
    fn read_cd(&mut self) -> Result<bool> {
        unimplemented!("serial ports unsupported")
    }
}

#[cfg(unix)]
impl std::os::unix::io::AsFd for SystemPort {
    fn as_fd(&self) -> std::os::unix::io::BorrowedFd<'_> {
        unimplemented!("serial ports unsupported")
    }
}

impl filedescriptor::AsRawFileDescriptor for SystemPort {
    fn as_raw_file_descriptor(&self) -> filedescriptor::RawFileDescriptor {
        unimplemented!("serial ports unsupported")
    }
}

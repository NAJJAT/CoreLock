/// Events emitted from eBPF programs to userspace via the ring buffer.
/// These structs must be `#[repr(C)]` and match the C structs in the
/// eBPF programs.

use std::net::Ipv4Addr;

#[derive(Debug, Clone)]
pub enum KernelEvent {
    PacketAllowed(PacketEvent),
    PacketBlocked(PacketEvent),
    DnsTunnelingSuspect(DnsEvent),
    ProcessSpawned(ProcessEvent),
    TlsClientHello(TlsEvent),
}

#[repr(C)]
#[derive(Debug, Clone, Copy)]
pub struct PacketEvent {
    pub pid:        u32,
    pub uid:        u32,
    pub src_ip:     u32,
    pub dst_ip:     u32,
    pub src_port:   u16,
    pub dst_port:   u16,
    pub protocol:   u8,
    pub direction:  u8,  // 0=egress 1=ingress
    pub action:     u8,  // 0=allow 1=drop
    pub _pad:       u8,
}

impl PacketEvent {
    pub fn src_addr(&self) -> Ipv4Addr { Ipv4Addr::from(u32::from_be(self.src_ip)) }
    pub fn dst_addr(&self) -> Ipv4Addr { Ipv4Addr::from(u32::from_be(self.dst_ip)) }
}

#[repr(C)]
#[derive(Debug, Clone, Copy)]
pub struct DnsEvent {
    pub pid:        u32,
    pub uid:        u32,
    pub dst_ip:     u32,
    pub entropy:    u64,    // fixed-point 16.16: entropy * 65536
    pub query_len:  u16,
    pub _pad:       [u8; 6],
}

#[repr(C)]
#[derive(Debug, Clone, Copy)]
pub struct ProcessEvent {
    pub pid:        u32,
    pub ppid:       u32,
    pub uid:        u32,
    pub comm:       [u8; 16], // process name (comm)
}

#[repr(C)]
#[derive(Debug, Clone, Copy)]
pub struct TlsEvent {
    pub pid:        u32,
    pub uid:        u32,
    pub dst_ip:     u32,
    pub dst_port:   u16,
    pub ja3_len:    u16,
    // ja3 hash follows in the ring buffer (variable length)
}

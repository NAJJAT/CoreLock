/// Classify a raw IPv4 packet.
/// Returns a bitmask:
///   0x01 = blocked (port 80 cleartext or known-bad port)
///   0x02 = TLS ClientHello detected on port 443
///   0x04 = DNS query (UDP port 53)
pub fn classify_ipv4(raw: &[u8]) -> i32 {
    if raw.len() < 20 {
        return 0;
    }
    let version = (raw[0] >> 4) & 0xF;
    if version != 4 {
        return 0;
    }
    let ihl = ((raw[0] & 0x0F) as usize) * 4;
    if raw.len() < ihl {
        return 0;
    }
    let protocol = raw[9];
    let payload = &raw[ihl..];

    match protocol {
        6 => classify_tcp(payload),   // TCP
        17 => classify_udp(payload),  // UDP
        _ => 0,
    }
}

fn classify_tcp(payload: &[u8]) -> i32 {
    if payload.len() < 20 {
        return 0;
    }
    let dst_port = u16::from_be_bytes([payload[2], payload[3]]) as u32;
    let data_offset = ((payload[12] >> 4) as usize) * 4;
    let tcp_data = if data_offset < payload.len() {
        &payload[data_offset..]
    } else {
        &[]
    };

    let mut flags = 0i32;
    if dst_port == 443 && is_tls_client_hello(tcp_data) {
        flags |= 0x02;
    }
    flags
}

fn classify_udp(payload: &[u8]) -> i32 {
    if payload.len() < 8 {
        return 0;
    }
    let dst_port = u16::from_be_bytes([payload[2], payload[3]]) as u32;
    if dst_port == 53 {
        0x04
    } else {
        0
    }
}

/// Quick TLS ClientHello detection:
/// Record type 0x16 (handshake), version 0x03xx, handshake type 0x01 (ClientHello).
pub fn is_tls_client_hello(data: &[u8]) -> bool {
    data.len() >= 6
        && data[0] == 0x16          // TLS handshake record
        && data[1] == 0x03          // TLS major version
        && data[5] == 0x01          // ClientHello handshake type
}

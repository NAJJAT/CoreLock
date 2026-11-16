package com.privacyguard.vpn.forwarder

import com.privacyguard.core.packet.IpPacket
import com.privacyguard.core.session.BlockSource
import com.privacyguard.core.session.Session

/**
 * Re-checks an open connection by the name it was opened for (F-9), so a Block rule or a
 * list update applies to connections that are already running, not only to new ones.
 * The decision is [reasonFor] = NameBlockPolicy, the same one DNS, SNI and QUIC use.
 *
 * Only names the connection itself sent are checked: the TLS/QUIC server name and the
 * HTTP site name. A name DNS gave the address out for is not, since one address serves
 * many sites. Runs from the service's once-a-second recheck, never per packet.
 */
object NameRecheck {

    /** The source to record if the connection is now blocked by its name, else null. */
    fun sourceFor(session: Session, reasonFor: (name: String, ownerPackage: String?) -> DnsBlockDecision.Reason?): BlockSource? {
        session.tlsSni?.let { sni ->
            if (reasonFor(sni, session.ownerPackage) != null) {
                return if (session.key.protocol == IpPacket.PROTO_UDP) BlockSource.QUIC else BlockSource.SNI
            }
        }
        session.httpHost?.let { host ->
            if (reasonFor(host, session.ownerPackage) != null) return BlockSource.HTTP
        }
        return null
    }
}

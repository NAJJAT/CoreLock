package com.privacyguard.app.data.repository

import com.privacyguard.app.data.db.ConnectionProfileDao
import com.privacyguard.app.data.db.ConnectionProfileEntity
import com.privacyguard.core.metadata.ConnectionProfile
import com.privacyguard.core.metadata.EncryptionStatus
import com.privacyguard.core.metadata.RiskCode
import com.privacyguard.core.metadata.RiskSignal
import com.privacyguard.core.metadata.TlsVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MetadataRepo(private val connectionProfileDao: ConnectionProfileDao) {

    suspend fun save(profile: ConnectionProfile) = withContext(Dispatchers.IO) {
        connectionProfileDao.upsert(profile.toEntity())
    }

    suspend fun saveAll(profiles: List<ConnectionProfile>) = withContext(Dispatchers.IO) {
        connectionProfileDao.upsertAll(profiles.map { it.toEntity() })
    }

    suspend fun recent(limit: Int = 100): List<ConnectionProfile> = withContext(Dispatchers.IO) {
        connectionProfileDao.allProfiles().map { it.toDomain() }
    }

    suspend fun profilesForApp(pkg: String): List<ConnectionProfile> = withContext(Dispatchers.IO) {
        connectionProfileDao.profilesForApp(pkg).map { it.toDomain() }
    }

    suspend fun riskyProfiles(min: Int = 40): List<ConnectionProfile> = withContext(Dispatchers.IO) {
        connectionProfileDao.riskyProfiles(min).map { it.toDomain() }
    }

    suspend fun cleartextProfiles(): List<ConnectionProfile> = withContext(Dispatchers.IO) {
        connectionProfileDao.cleartextProfiles().map { it.toDomain() }
    }

    suspend fun search(q: String): List<ConnectionProfile> = withContext(Dispatchers.IO) {
        connectionProfileDao.search(q).map { it.toDomain() }
    }

    suspend fun trackedPackages(): List<String> = withContext(Dispatchers.IO) {
        connectionProfileDao.trackedPackages()
    }

    suspend fun pruneOld(maxAgeMs: Long) = withContext(Dispatchers.IO) {
        connectionProfileDao.deleteOlderThan(System.currentTimeMillis() - maxAgeMs)
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        connectionProfileDao.deleteAll()
    }

    fun toDomainForDashboard(entity: ConnectionProfileEntity): ConnectionProfile = entity.toDomain()

    private fun ConnectionProfile.toEntity() = ConnectionProfileEntity(
        packageName = packageName,
        hostname = hostname,
        destinationIp = destinationIp,
        destinationPort = destinationPort,
        connectionCount = connectionCount,
        totalBytesOut = totalBytesOut,
        totalBytesIn = totalBytesIn,
        avgBytesPerConnection = avgBytesPerConnection,
        firstSeen = firstSeen,
        lastSeen = lastSeen,
        avgIntervalMs = avgIntervalMs,
        minIntervalMs = minIntervalMs,
        backgroundRatio = backgroundRatio,
        hourlyDistribution = hourlyDistribution.joinToString(","),
        encryptionStatus = encryptionStatus.name,
        tlsVersion = tlsVersion?.name,
        sniHostname = sniHostname,
        riskScore = riskScore,
        riskSignalCodes = riskSignals.joinToString(",") { it.code.name },
        updatedAt = System.currentTimeMillis(),
    )

    private fun ConnectionProfileEntity.toDomain(): ConnectionProfile {
        val hours = hourlyDistribution.split(",").map { it.toIntOrNull() ?: 0 }.toIntArray().let {
            if (it.size == 24) it else IntArray(24)
        }
        val encStat = runCatching { EncryptionStatus.valueOf(encryptionStatus) }.getOrDefault(EncryptionStatus.UNKNOWN)
        val tlsVer = tlsVersion?.let { runCatching { TlsVersion.valueOf(it) }.getOrNull() }
        val signals = riskSignalCodes.split(",").filter { it.isNotEmpty() }.mapNotNull { code ->
            runCatching { RiskCode.valueOf(code) }.getOrNull()?.let { RiskSignal(it, code, 5) }
        }
        return ConnectionProfile(
            packageName = packageName,
            hostname = hostname,
            destinationIp = destinationIp,
            destinationPort = destinationPort,
            connectionCount = connectionCount,
            totalBytesOut = totalBytesOut,
            totalBytesIn = totalBytesIn,
            avgBytesPerConnection = avgBytesPerConnection,
            firstSeen = firstSeen,
            lastSeen = lastSeen,
            avgIntervalMs = avgIntervalMs,
            minIntervalMs = minIntervalMs,
            backgroundRatio = backgroundRatio,
            hourlyDistribution = hours,
            encryptionStatus = encStat,
            tlsVersion = tlsVer,
            sniHostname = sniHostname,
            riskScore = riskScore,
            riskSignals = signals,
        )
    }
}

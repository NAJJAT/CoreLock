package com.privacyguard.vpn.interception

/**
 * What the user must be told about HTTPS inspection. Decrypting traffic without a
 * visible sign would break the consent promise, so the VPN notification and the
 * dashboard show anything but [OFF], and say when the organisation turned it on.
 */
enum class InspectionStatus { OFF, ON, ON_BY_ORGANISATION }

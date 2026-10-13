package dev.dsh.pocket.protocol

/** Plugin/app versions exchanged during hello/pair. */
data class HandshakeVersions(
    val proto: Int,
    val pluginVersion: String,
    val minimumAppVersion: String,
)

enum class CompatibilityIssueKind {
    APP_TOO_OLD,
    PLUGIN_TOO_OLD,
    PROTOCOL_UNSUPPORTED,
}

data class CompatibilityIssue(
    val kind: CompatibilityIssueKind,
    val requiredVersion: String? = null,
)

/**
 * Compares hello/pair version fields. Malformed or unknown protocol metadata is
 * treated as unsupported rather than requesting the wrong update.
 */
object VersionCompatibility {
    const val PROTO = 1
    const val MINIMUM_PLUGIN_VERSION = "0.1.0"
    const val MINIMUM_APP_VERSION = "0.1.0"

    fun evaluate(appVersion: String, handshake: HandshakeVersions): CompatibilityIssue? {
        if (handshake.proto != PROTO) {
            return CompatibilityIssue(CompatibilityIssueKind.PROTOCOL_UNSUPPORTED)
        }
        if (parse(appVersion) == null ||
            parse(handshake.pluginVersion) == null ||
            parse(handshake.minimumAppVersion) == null
        ) {
            return CompatibilityIssue(CompatibilityIssueKind.PROTOCOL_UNSUPPORTED)
        }
        if (compareVersions(appVersion, handshake.minimumAppVersion) < 0) {
            return CompatibilityIssue(CompatibilityIssueKind.APP_TOO_OLD, handshake.minimumAppVersion)
        }
        if (compareVersions(handshake.pluginVersion, MINIMUM_PLUGIN_VERSION) < 0) {
            return CompatibilityIssue(CompatibilityIssueKind.PLUGIN_TOO_OLD, MINIMUM_PLUGIN_VERSION)
        }
        return null
    }

    fun compareVersions(left: String, right: String): Int {
        val leftParts = parse(left) ?: return -1
        val rightParts = parse(right) ?: return 1
        for (index in leftParts.indices) {
            val compared = leftParts[index].compareTo(rightParts[index])
            if (compared != 0) return compared
        }
        return 0
    }

    private fun parse(value: String): List<Int>? {
        val match = VERSION.matchEntire(value.trim()) ?: return null
        return (1..3).map { match.groupValues[it].toIntOrNull() ?: return null }
    }

    private val VERSION = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)(?:[-+].*)?$")
}

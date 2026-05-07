package com.gimy.tv.data.update

/** Lightweight semantic version (x.y.z). Lenient parser that strips a leading 'v' and trims. */
data class SemVer(val major: Int, val minor: Int, val patch: Int) : Comparable<SemVer> {
    override fun compareTo(other: SemVer): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        return patch.compareTo(other.patch)
    }

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        fun parse(raw: String?): SemVer? {
            if (raw.isNullOrBlank()) return null
            val cleaned = raw.trim().removePrefix("v").removePrefix("V")
            val parts = cleaned.split('.', '-', '+').take(3)
            if (parts.size < 2) return null
            val major = parts[0].toIntOrNull() ?: return null
            val minor = parts[1].toIntOrNull() ?: return null
            val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
            return SemVer(major, minor, patch)
        }
    }
}

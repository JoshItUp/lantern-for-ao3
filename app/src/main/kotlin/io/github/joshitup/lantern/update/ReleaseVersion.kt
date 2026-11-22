package io.github.joshitup.lantern.update

/** A release version `X.Y.Z`; the build derives versionCode with the same formula as [code]. */
data class ReleaseVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<ReleaseVersion> {
    val code: Int get() = major * 10000 + minor * 100 + patch

    override fun compareTo(other: ReleaseVersion): Int = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val TAG = Regex("""^v?(\d+)\.(\d+)\.(\d+)$""")

        /** Accepts `v1.2.3` or `1.2.3` (minor and patch below 100); anything else is null. */
        fun parseTag(tag: String): ReleaseVersion? {
            val m = TAG.matchEntire(tag) ?: return null
            val (major, minor, patch) = m.destructured
            val ma = major.toIntOrNull() ?: return null
            val mi = minor.toIntOrNull() ?: return null
            val pa = patch.toIntOrNull() ?: return null
            if (mi >= 100 || pa >= 100) return null
            return ReleaseVersion(ma, mi, pa)
        }
    }
}

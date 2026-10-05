package bluebit.core.bluetooth

enum class BleLogLevel { DEBUG, INFO, WARN, ERROR }

enum class BleLogTag {
    SCAN, CONNECTION, GATT_SERVICES, GATT_READ, GATT_WRITE,
    GATT_NOTIFY, GATT_DESCRIPTOR, BOND, PROVIDER_CONNECTOR,
}

data class BleLogEntry(
    val timestampMs: Long,
    val level: BleLogLevel,
    val tag: BleLogTag,
    val message: String,
) {
    /** Formats timestamp as ISO-8601 (yyyy-MM-dd HH:mm:ss.SSS) using local system time. */
    fun formattedTimestamp(): String {
        val ms = timestampMs
        val s = ms / 1000
        val minutes = s / 60
        val hours = (minutes / 60) % 24
        val daysSinceEpoch = (minutes / 60 / 24).toInt()

        // Simple date calculation for days since 1970-01-01
        var y = 1970
        var remainingDays = daysSinceEpoch
        while (true) {
            val yearDays = if (isLeap(y)) 366 else 365
            if (remainingDays < yearDays) break
            remainingDays -= yearDays
            y++
        }
        val monthLengths = intArrayOf(31, if (isLeap(y)) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        var m = 1
        for (len in monthLengths) {
            if (remainingDays < len) break
            remainingDays -= len
            m++
        }
        val d = remainingDays + 1

        val hh = (hours % 24).toInt()
        val mm = (minutes % 60).toInt()
        val ss = (s % 60).toInt()
        val mss = (ms % 1000).toInt()

        fun pad2(n: Int) = n.toString().padStart(2, '0')
        fun pad3(n: Int) = n.toString().padStart(3, '0')
        return "$y-${pad2(m)}-${pad2(d)} ${pad2(hh)}:${pad2(mm)}:${pad2(ss)}.${pad3(mss)}"
    }

    companion object {
        private fun isLeap(year: Int): Boolean = (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0)
    }
}

class BleLogWatchdog(capacity: Int = 2000) {
    private val buffer = ArrayDeque<BleLogEntry>(capacity)
    private val lock = Any()
    private val maxCapacity = capacity

    fun log(level: BleLogLevel, tag: BleLogTag, message: String) {
        val entry = BleLogEntry(System.currentTimeMillis(), level, tag, message)
        synchronized(lock) {
            if (buffer.size >= maxCapacity) buffer.removeFirst()
            buffer.addLast(entry)
        }
    }

    fun debug(tag: BleLogTag, message: String) = log(BleLogLevel.DEBUG, tag, message)
    fun info(tag: BleLogTag, message: String) = log(BleLogLevel.INFO, tag, message)
    fun warn(tag: BleLogTag, message: String) = log(BleLogLevel.WARN, tag, message)
    fun error(tag: BleLogTag, message: String) = log(BleLogLevel.ERROR, tag, message)

    fun dump(): List<BleLogEntry> = synchronized(lock) { buffer.toList() }

    fun clear() = synchronized(lock) { buffer.clear() }

    fun formatEntries(): String = dump().joinToString("\n") {
        "${it.formattedTimestamp()} [${it.level}] ${it.tag}: ${it.message}"
    }
}

val bleLogWatchdog = BleLogWatchdog()

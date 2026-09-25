package mlogix.util

object Log {
    var level: LogType = LogType.INFO // 最低日志等级

    fun isAllowed(type: LogType): Boolean {
        return level.ordinal <= type.ordinal
    }

    fun debug(log: String?) {
        if (level.ordinal <= LogType.DEBUG.ordinal) {
            println(log)
        }
    }

    fun info(log: String?) {
        if (level.ordinal <= LogType.INFO.ordinal) {
            println(log)
        }
    }

    fun warning(log: String?) {
        if (level.ordinal <= LogType.WARNING.ordinal) {
            println(log)
        }
    }

    fun error(log: String?) {
        if (level.ordinal <= LogType.ERROR.ordinal) {
            println(log)
        }
    }

    enum class LogType {
        DEBUG,
        INFO,
        WARNING,
        ERROR
    }
}
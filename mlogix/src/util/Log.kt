package mlogix.util;

public class Log {
    static public LogType level = LogType.INFO; // 最低日志等级

    public static boolean isAllowed(LogType type) {
        return level.ordinal() <= type.ordinal();
    }

    public static void setLevel(LogType type) {
        level = type;
    }

    public static void debug(String log) {
        if (level.ordinal() <= LogType.DEBUG.ordinal()) {
            System.out.println(log);
        }
    }

    public static void info(String log) {
        if (level.ordinal() <= LogType.INFO.ordinal()) {
            System.out.println(log);
        }
    }

    public static void warning(String log) {
        if (level.ordinal() <= LogType.WARNING.ordinal()) {
            System.out.println(log);
        }
    }

    public static void error(String log) {
        if (level.ordinal() <= LogType.ERROR.ordinal()) {
            System.out.println(log);
        }
    }

    public enum LogType {
        DEBUG,
        INFO,
        WARNING,
        ERROR
    }
}
package mlogix.util

object Ansi {
    const val DEFAULT: String = "\u001b[0m"        //默认
    const val BRIGHT: String = "\u001b[1m"         //高亮
    const val BOLD: String = "\u001b[2m"           //粗体
    const val UNDERLINE: String = "\u001b[4m"      //下划线
    const val BLINK: String = "\u001b[5m"          //闪烁
    const val REVERSED: String = "\u001b[7m"       //反转
    const val INVISIBLE: String = "\u001b[8m"      //不可见
    const val NON_BOLD: String = "\u001b[22m"      //非粗体
    const val NON_UNDERLINE: String = "\u001b[24m" //非下划线
    const val NON_BLINK: String = "\u001b[25m"     //非闪烁
    const val NON_REVERSED: String = "\u001b[27m"  //非反转
    const val VISIBLE: String = "\u001b[28m"       //可见

    const val BLACK: String = "\u001b[30m"   //黑色
    const val RED: String = "\u001b[31m"     //红色
    const val GREEN: String = "\u001b[32m"   //绿色
    const val YELLOW: String = "\u001b[33m"  //黄色
    const val BLUE: String = "\u001b[34m"    //蓝色
    const val MAGENTA: String = "\u001b[35m" //洋红色
    const val CYAN: String = "\u001b[36m"    //青色
    const val GRAY: String = "\u001b[37m"    //灰色

    //背景色
    const val B_BLACK: String = "\u001b[40m"   //黑色
    const val B_RED: String = "\u001b[41m"     //红色
    const val B_GREEN: String = "\u001b[42m"   //绿色
    const val B_YELLOW: String = "\u001b[43m"  //黄色
    const val B_BLUE: String = "\u001b[44m"    //蓝色
    const val B_MAGENTA: String = "\u001b[45m" //洋红色
    const val B_CYAN: String = "\u001b[46m"    //青色
    const val B_WHITE: String = "\u001b[47m"   //白色

    //光标移动
    fun UP(n: Int): String {
        return "\u001b[" + n + "A"
    }

    fun DOWN(n: Int): String {
        return "\u001b[" + n + "B"
    }

    fun RIGHT(n: Int): String {
        return "\u001b[" + n + "C"
    }

    fun LEFT(n: Int): String {
        return "\u001b[" + n + "D"
    }

    const val SAVE_CURSOR: String = "\u001b[s"     //保存光标位置
    const val ROLLBACK_CURSOR: String = "\u001b[u" //恢复光标位置
    const val HIDE_CURSOR: String = "\u001b[?25l"  //隐藏光标
    const val SHOW_CURSOR: String = "\u001b[?25h"  //显示光标

    //移动光标
    fun MOVE_CURSOR(x: Int, y: Int): String {
        return "\u001b[" + y + ";" + x + "H"
    }

    const val CLEAR_END: String = "\u001b[0J"
    const val CLEAR_START: String = "\u001b[1J"
    const val CLEAR: String = "\u001b[2J"

    const val CLEAR_LINE_END: String = "\u001b[0J"
    const val CLEAR_LINE_START: String = "\u001b[1J"
    const val CLEAR_LINE: String = "\u001b[2J"
}
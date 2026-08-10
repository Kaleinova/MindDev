package mlogix.util

object Printer {
    fun printLogic(code: String) {
        val lines: Array<String?> = code.split("\n".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        val lineIdDigitNum = lines.size.toString().length
        var row = 0
        for (line in lines) {
            print(Ansi.CYAN)
            print(" ")
            (lineIdDigitNum - row.toString().length)

            print(row.toString() + "┃" + Ansi.DEFAULT)
            println(line)
            row++
        }
    }

    fun printText(code: String) {
        val lines: Array<String?> = code.split("\n".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        val lineIdDightNum = lines.size.toString().length
        var row = 1
        for (line in lines) {
            print(Ansi.CYAN)
            print(" ".repeat(lineIdDightNum - row.toString().length))
            print(row.toString() + "┃" + Ansi.DEFAULT)
            println(line)
            row++
        }
    }
}
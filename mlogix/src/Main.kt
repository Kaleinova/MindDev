package mlogix;

import arc.files.Fi;
import mlogix.compiler.Compiler;
import mlogix.util.*;
import mlogix.util.Log;

public class Main {
    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("未传入参数");
            return;
        }

        if (args.length >= 2) {
            switch (args[1]) {
                case "d":
                    Log.setLevel(Log.LogType.DEBUG);
                    break;
            }
        }

        switch (args[0]) {
            case "c":
                compile();
                break;
        }
    }

    static void compile() {
        // 获取当前工作目录
        Fi projectDirectory = Fi.get(System.getProperty("user.dir"));

        Compiler compiler = new Compiler(projectDirectory);
        boolean result = compiler.compile();
    }
}
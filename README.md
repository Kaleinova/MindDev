# Mindustry MindDev

**中文** | [English](README_en.md)

一个同时兼容 Android 与 桌面端 的 [Mindustry](https://github.com/anuken/mindustry) 模组，提供一套逻辑编辑器。

目前仍在开发第一个版本。

---

## 内置高级语言：MLogiX

**MLogiX** 旨在提供**便利的逻辑编辑**与**高等特性**。

[语法（施工中）](mlogix/docs/grammar/index.md)

---

## 构建指南

当你需要自行构建而非使用发行版时阅读。

### 测试 MLogiX 编译器

- 基本测试：运行 `./gradlew mlogix:test`
- 测试具体源码:
  1. 在 `mlogix` 模块下创建文件 `test.mlx` 并写入测试源码。
  2. 运行 `./gradlew mlogix:compile` 或 `./gradlew mlogix:compile-debug`

### 桌面端测试构建（仅 PC）

适用于快速调试和本地试用，**生成的 `.jar` 文件无法在 Android 上运行**。

1. 安装 **JDK 17**（或更高版本）。
2. 在项目根目录下执行构建命令 `./gradlew jar`
3. 构建完成后，模组文件位于 `build/libs/` 目录下。
4. （可选）若已配置模组输出目录，构建时会自动将 `.jar` 复制到游戏模组文件夹中。

**配置自动复制：**  
在项目根目录创建 `local.properties` 文件，添加如下内容（注意路径中的反斜杠需转义）：
```properties
modsDir=C:\\Users\\用户名\\AppData\\Roaming\\Mindustry\\mods
```
*（Linux / macOS 示例：`modsDir=/home/用户名/.local/share/Mindustry/mods`）*

---

### 部署构建（Android + 桌面）

此版本生成的 `.jar` 可同时在 **Android** 和 **桌面端** 运行，但需要额外配置 Android 开发环境。

**前置要求：**

- **Android SDK**
- **API 级别 30**
- **任一版本的 Build Tools**（例如 `30.0.1` 或更新版本）

1. 下载并解压 Android SDK，设置环境变量 `ANDROID_HOME` 指向解压目录。  
   （例如：`C:\Android\sdk` 或 `/home/用户名/Android/Sdk`）
2. 确保已安装 **API 30** 和 **Build Tools**（如 `30.0.1`）。
3. 将 `$ANDROID_HOME/build-tools/版本号/`（如 `30.0.1`）添加到系统的 `PATH` 环境变量中。
4. 在项目根目录运行部署命令：
    - **Windows**：`gradlew deploy`
    - **Linux / macOS**：`./gradlew deploy`
5. 若配置无误，生成的 `jar` 文件将出现在 `build/libs/` 中，该文件可直接用于 **Android 设备** 和 **PC 端**。

---

### 贡献 & 反馈

欢迎提交 Issue 或 Pull Request，帮助改进本项目！

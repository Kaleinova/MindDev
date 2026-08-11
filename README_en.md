# Mindustry MindDev

[中文](README.md) | **English**

A [Mindustry](https://github.com/anuken/mindustry) mod that is compatible with both Android and desktop platforms, providing a suite of logic editors.

The first version is still under development.

---

## Built-in High-Level Language: MLogiX

**MLogiX** aims to provide **convenient logic editing** and **advanced features**.

[Syntax (under construction)](mlogix/docs/grammar/index.md)

---

## Build Guide

Read this when you need to build manually rather than using a release version.

### Test MLogiX Compiler

- Basic test: run `./gradlew test`
- Test specific source code:
   1. Create the file `test.mlx` under the `mlogix` module and write your test source code in it.
   2. Run `./gradlew compile` or `./gradlew compile-debug`

### Desktop Test Build (PC only)

Suitable for fast debugging and local trials. **The generated `.jar` file will not run on Android**.

1. Install **JDK 17** (or higher).
2. Run the build command in the project root directory `./gradlew jar`
3. After the build completes, the mod file is located in the `build/libs/` directory.
4. (Optional) If you have configured a mod output directory, the `.jar` will be automatically copied to the game's mod folder during build.

**Configuring auto-copy:**  
Create a `local.properties` file in the project root and add the following (escape backslashes in the path):
```properties
modsDir=C:\\Users\\YourUsername\\AppData\\Roaming\\Mindustry\\mods
```
*(Linux / macOS example: `modsDir=/home/YourUsername/.local/share/Mindustry/mods`)*

---

### Deployment Build (Android + Desktop)

This version generates a `.jar` that works on both **Android** and **desktop**, but requires additional Android development environment setup.

**Prerequisites:**

- **Android SDK**
- **API Level 30**
- **Any version of Build Tools** (e.g., `30.0.1` or newer)

1. Download and extract the Android SDK, then set the environment variable `ANDROID_HOME` to point to the extracted directory.  
   (e.g., `C:\Android\sdk` or `/home/YourUsername/Android/Sdk`)
2. Ensure that **API 30** and **Build Tools** (e.g., `30.0.1`) are installed.
3. Add `$ANDROID_HOME/build-tools/version/` (e.g., `30.0.1`) to your system `PATH` environment variable.
4. Run the deployment command in the project root `./gradlew deploy`
5. If configured correctly, the generated `jar` file will appear in `build/libs/` and can be used directly on **Android devices** and **PC**.

---

### Contributions & Feedback

Issues and Pull Requests are welcome to help improve this project!
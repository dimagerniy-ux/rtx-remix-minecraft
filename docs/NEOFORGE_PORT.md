# Experimental NeoForge 1.21.1 port (NVIDIA RTX Remix)

This branch adapts the NVIDIA RTX Remix for Minecraft source to **Minecraft Java 1.21.1** and **NeoForge 21.1.265**. It is an unofficial work in progress, not an NVIDIA release. Native rendering, in-game compatibility and the NVIDIA DLSS/NGX payload must be validated before distribution.

## Testing a build

1. In GitHub Actions, open a successful **build** workflow run for this branch. Download the **rtx-remix-neoforge-1.21.1** artifact, which contains a distributable JAR from `build/distributions/`.
2. Create a **separate, disposable** Minecraft 1.21.1 / NeoForge 21.1.265 client profile. Do not use your existing modpack, saves, or shared `.minecraft` folder for the first launch.
3. Copy only the distributable JAR into that profile's `mods` folder. It includes the native NVIDIA runtime payload; the lightweight `build/libs/` JAR does not.
4. Launch on Windows with compatible GeForce RTX hardware. If the mod loads, inspect `latest.log`, check `/rtx status`, and verify that the renderer initializes and draws a scene. Then check texture loading, controls, and frame presentation.
5. Only after that, make a separate test profile with Create and the other NeoForge mods; rendering-engine and shader compatibility is **not** established by a successful Gradle build.

**Do not run the repository's existing `install.bat` or `installer.ps1` for this port.** Those scripts, together with the installer bundle instructions, still configure **Fabric** and have **not** been ported to NeoForge. For this branch, use the JAR in a separately configured NeoForge profile.

The GitHub Actions pipeline runs Java compilation, JVM tests, distribution packaging validation, and the project tooling tests. A green build proves those checks passed, **not** that the RTX runtime functions in Minecraft.

<H1 align="center">MojoLauncher (a.k.a. MJLauncher)</H1>

<a href="./README_RU.md">Readme на русском</a>

<img src="./app_pojavlauncher/src/main/assets/pojavlauncher.png" align="left" width="150" height="150" alt="MojoLauncher logo">

[![Android CI](https://github.com/MojoLauncher/MojoLauncher/workflows/Android%20CI/badge.svg)](https://github.com/MojoLauncher/MojoLauncher/actions)
[![GitHub commit activity](https://img.shields.io/github/commit-activity/m/MojoLauncher/MojoLauncher)](https://github.com/MojoLauncher/MojoLauncher/actions)
[![Discord](https://img.shields.io/discord/1365346109131722753.svg?label=&logo=discord&logoColor=ffffff&color=7389D8&labelColor=6A7EC2)](https://discord.gg/VHdwQFsaGX)

* MojoLauncher is a launcher, based on [PojavLauncher](https://github.com/PojavLauncherTeam/PojavLauncher), that allows you to play Minecraft: Java Edition on your Android device!

* It can run almost every version of Minecraft, allowing you to use .jar only installers to install modloaders such as [Forge](https://files.minecraftforge.net/) and [Fabric](http://fabricmc.net/) and mods like [OptiFine](https://optifine.net).

## Navigation
- [Introduction](#introduction)
- [Getting MojoLauncher](#getting-mojolauncher)
- [Building](#building) 
- [Current roadmap](#current-roadmap) 
- [License](#license) 
- [Contributing](#contributing) 
- [Credits & Third party components and their licenses](#credits--third-party-components-and-their-licenses-if-available)

## Introduction 
* MojoLauncher is a Minecraft: Java Edition launcher for Android based on [PojavLauncher](https://github.com/PojavLauncherTeam/PojavLauncher)
* This launcher can launch almost all available Minecraft versions ranging from rd-132211 to 26.x snapshots (including Combat Test versions). 
* Modding via Forge and Fabric are also supported. 

## Getting MojoLauncher

You can get MojoLauncher via four methods:

1. You can get the prebuilt app from the [releases section](http://github.com/mojolauncher/mojolauncher/releases).

2. You can get it from Google Play by clicking on this badge:
[![Google Play](https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png)](https://play.google.com/store/apps/details?id=git.artdeell.mjlaunch)

3. You can get early builds from [Github Actions](http://github.com/mojolauncher/mojolauncher/actions).

4. You can [build](#building) from source.
## Building   

### Android APK

Local/Replit build (skips remote asset downloads — sufficient for most dev work):
```
./gradlew :app_pojavlauncher:assembleDebug
```

Full build with all remote assets (CI/CD only — requires network and signing env vars):
```
./gradlew :app_pojavlauncher:assembleDebug -PdownloadAssets=true
```
(Replace `./gradlew` with `.\gradlew.bat` on Windows.)

**Signing keys:** Never commit `.jks` or `.keystore` files. Provide them via CI secrets (`UPLOAD_KEYSTORE_PASSWORD`, `MOJOPUB_KEYSTORE_PASSWORD`).

### Server-hosting prototype (runs directly on Linux / Replit)
```bash
cd server-prototype
./build.sh    # compile sources
./test.sh     # run all tests (8 tests, all pass)
./run.sh      # launch interactive CLI server manager
```
Server jars are always downloaded at runtime — never bundled (licensing compliance). EULA acceptance is always required before a server can start.

## Current roadmap
- [x] Instance system in favor of profiles
- [x] Out-of-the box 1.21.5 support
- [x] mrpack/CurseForge zip import
- [ ] LTW: resolve issues with Create
- [ ] LTW: enable compute shader/image extensions
- [ ] LTW: switch to a color-renderable format for framebuffers
- [ ] Modpack/mod management tool
- [ ] MMC-compatible instance import
- [ ] Implement common native library standard

## Known Issues
- Some physical mice may have very slow mouse speed
- On Holy GL4ES, large texture atlases may be distorted (resulting in stretched/blocky textures in modpacks)
- Probably more, that's why we have a bug tracker ;) 

## Server Hosting (prototype)

MojoLauncher now includes a **server-hosting prototype** — a plain-Java module that can
create, download, start, stop, and back up headless Minecraft servers (Paper or Vanilla).

### Quick start (Replit / Linux)

```bash
cd server-prototype
chmod +x build.sh run.sh test.sh
./build.sh          # compile
./run.sh            # interactive CLI
./test.sh           # run all tests (8 tests, all pass)
```

### Interactive CLI commands

| Command | Description |
|---------|-------------|
| `create` | Create a new server instance (downloads jar, writes eula.txt) |
| `list` | List all instances with current status |
| `start` | Start a server (non-blocking, logs stream in background) |
| `stop` | Graceful stop: save-all → stop → wait 30 s → force kill |
| `console` | Attach interactive console to a running server |
| `backup` | Zip world/ to `backups/<timestamp>.zip` |
| `versions` | List available Paper or Vanilla versions |
| `delete` | Delete an instance and all its data |

### Android integration

The Android-side classes are added in the existing module packages:

- `instances/ServerInstance.java` — per-server metadata and directory layout
- `downloader/ServerJarDownloader.java` — Paper API + Mojang manifest downloads
- `lifecycle/ServerProcessManager.java` — start/stop/crash detection
- `fragments/ServerListFragment.java` — server list UI
- `fragments/ServerConsoleFragment.java` — real-time console + command input

See [`server-prototype/README.md`](server-prototype/README.md) for full architecture details.

## License
- MojoLauncher is licensed under [GNU LGPLv3](https://github.com/MojoLauncher/MojoLauncher/blob/v3_openjdk/LICENSE).

## Contributing
Contributions are welcome! We welcome any type of contribution, not only code. For example, you can help the wiki shape up. You can help the [translation](https://crowdin.com/project/pojavlauncher) too!


Any code change to this repository should be submitted as a pull request. The description should explain what the code does and give steps to execute it.

## Third party components, licenses and sources (when applicable)
- [PojavLauncher](https://github.com/PojavLauncherTeam/PojavLauncher): [GNU LGPLv3 License](https://github.com/PojavLauncherTeam/PojavLauncher/blob/v3_openjdk/LICENSE)
- [Boardwalk](https://github.com/zhuowei/Boardwalk) (JVM Launcher): Unknown License/[Apache License 2.0](https://github.com/zhuowei/Boardwalk/blob/master/LICENSE) or GNU GPLv2.
- Android Support Libraries: [Apache License 2.0](https://android.googlesource.com/platform/prebuilts/maven_repo/android/+/master/NOTICE.txt).
- [Holy GL4ES](https://github.com/artdeell/gl4es_extra_extra/): [MIT License](https://github.com/ptitSeb/gl4es/blob/master/LICENSE).<br>
- [OpenJDK](https://github.com/PojavLauncherTeam/openjdk-multiarch-jdk8u): [GNU GPLv2 License](https://openjdk.java.net/legal/gplv2+ce.html).<br>
- [GLFW](https://github.com/MojoLauncher/glfw): [zlib license](https://github.com/MojoLauncher/glfw/blob/glfw34/LICENSE.md)
- [LWJGL2-GLFW](https://github.com/MojoLauncher/lwjgl2-glfw): 3-Clause BSD license
- [LWJGL3](https://github.com/LWJGL/lwjgl3): [BSD-3 License](https://github.com/LWJGL/lwjgl3/blob/master/LICENSE.md).
- [Mesa 3D Graphics Library](https://gitlab.freedesktop.org/mesa/mesa): [MIT License](https://docs.mesa3d.org/license.html).
- [pro-grade](https://github.com/pro-grade/pro-grade) (Java sandboxing security manager): [Apache License 2.0](https://github.com/pro-grade/pro-grade/blob/master/LICENSE.txt).
- [bhook](https://github.com/bytedance/bhook) (Used for exit code trapping): [MIT license](https://github.com/bytedance/bhook/blob/main/LICENSE).
- [Authlib-Injector](https://github.com/yushijinhun/authlib-injector) (Used for authorisation via ely.by): [AGPL-3.0](https://github.com/yushijinhun/authlib-injector/blob/develop/LICENSE).
- [alsoft](https://github.com/kcat/openal-soft/) (Audio output library): [GNU LIBRARY GENERAL PUBLIC LICENSE](https://github.com/kcat/openal-soft/blob/master/COPYING) and [modified PFFFT](https://github.com/kcat/openal-soft/blob/master/LICENSE-pffft).
- [oboe](https://github.com/google/oboe): [Apache License 2.0](https://github.com/google/oboe/blob/main/LICENSE).
- Thanks to [Mineskin](https://mineskin.eu/) for providing Minecraft avatars.

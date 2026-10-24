# BDIX Stream — CloudStream 3 Plugin

This is a custom CloudStream 3 plugin designed to browse, search, and stream media from your ISP's BDIX Dhaka Flix FTP servers (`172.16.50.x`).

## Features
- **Full Category Support**:
  - English Movies (Standard & 1080p)
  - IMDb Top 250 Movies
  - Hindi Movies & South Indian Hindi Dubbed
  - Foreign Language & Kolkata Bangla Movies (including Satyajit Ray Films)
  - TV & Web Series & Korean Web Series
  - Anime & Cartoon TV Series
  - Animation Movies & 3D Movies
  - Documentary, WWE & AEW Wrestling, Awards & TV Shows
- **Smart Directory Index Parsing**: Automatically discovers movie/series folders, direct video files, and thumbnails in the same folder.
- **TV Series & Episode Extraction**: Automatically detects season and episode numbers (`S01E01`, `Season 1`, etc.) across subfolders.
- **In-Memory Caching & Fast Search**: Fast recursive directory scanning with bounded search depth to avoid overloading local BDIX servers.
- **Cleartext Traffic Support**: Configured with `android:usesCleartextTraffic="true"` to work with local HTTP `http://172.16.50.x` servers.

## How to Build
To build the `.cs3` plugin file:
- On Windows: `.\gradlew.bat BDIXStream:make`
- On Linux/Mac: `./gradlew BDIXStream:make`

The compiled plugin will be located at:
`BDIXStream/build/BDIXStream.cs3`

## How to Install in CloudStream
1. Transfer `BDIXStream.cs3` to your Android phone (or deploy directly via ADB using `.\gradlew.bat BDIXStream:deployWithAdb`).
2. Open CloudStream -> Settings -> Extensions.
3. Tap **Install from file** and select `BDIXStream.cs3`.
4. Make sure your phone is connected to your ISP's Wi-Fi network to access `172.16.50.x`.

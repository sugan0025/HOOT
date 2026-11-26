<!-- ========================================================================= -->
<!-- HOOT: FAST LOCAL FILE CONVERTER FOR WINDOWS, MACOS & LINUX                -->
<!-- ========================================================================= -->

<div align="center">

# 🦉 HOOT — Fast Local File Converter

*Convert, optimize, and transform your files in a hoot.*

*Proudly watched over by our Blobatar Owl mascot.*

<br>

<a href="https://git.io/typing-svg">
  <img src="https://readme-typing-svg.demolab.com?font=Fira+Code&weight=600&size=19&duration=3000&pause=1000&color=60A5FA&center=true&vCenter=true&repeat=true&width=750&height=45&lines=Liquid+Glass+Aesthetic+%E2%80%A2+Ambient+Backdrop+Blur+%26+Specular+Borders;Instant+Drag+%26+Drop+Donut+Wheel+%E2%80%A2+Subtle+Synthesized+Audio+Chimes;Clipboard+Auto-Detector+%E2%80%A2+Quick+Conversion+Notification+HUD;100%25+Offline+Local+Engine+%E2%80%A2+Zero+Telemetry+%E2%80%A2+Windows+%26+macOS" alt="HOOT Typist" />
</a>

<br><br>

<!-- Badges: Platform, Runtime, Theme & License -->
<a href="https://dotnet.microsoft.com/">
  <img src="https://img.shields.io/badge/.NET_10-Avalonia_UI-512BD4?style=for-the-badge&logo=dotnet&logoColor=white" alt=".NET 10 Avalonia UI" />
</a>
&nbsp;
<a href="https://github.com/sugan0025/HOOT/releases">
  <img src="https://img.shields.io/badge/Platform-Windows_%7C_macOS_%7C_Linux-0078D6?style=for-the-badge&logo=windows&logoColor=white" alt="Windows & macOS" />
</a>
&nbsp;
<a href="https://github.com/sugan0025/HOOT/tree/release-binaries">
  <img src="https://img.shields.io/badge/Theme-Liquid_Glass_Design-38BDF8?style=for-the-badge&logo=css3&logoColor=white" alt="Liquid Glass Design" />
</a>
&nbsp;
<img src="https://img.shields.io/badge/Privacy-100%25_Offline_Local-16A34A?style=for-the-badge&logo=shield&logoColor=white" alt="100% Offline Local" />
&nbsp;
<a href="./LICENSE">
  <img src="https://img.shields.io/badge/License-MIT-F59E0B?style=for-the-badge&logo=open-source-initiative&logoColor=white" alt="MIT License" />
</a>

<br><br>

<p align="center">
  <b>A modern, high-performance offline desktop file converter engineered with a custom frosted Liquid Glass interface, synthesized audio chimes, clipboard auto-detection, and native multiplatform support for Windows, macOS, and Linux. Proudly watched over by our Blobatar Owl mascot.</b>
</p>

[📥 Downloads](#-pre-built-downloads) &nbsp;•&nbsp; [✨ Features](#-features) &nbsp;•&nbsp; [🎯 How It Works](#-how-it-works) &nbsp;•&nbsp; [🚀 Quick Start](#-quick-start) &nbsp;•&nbsp; [📁 Architecture](#-project-architecture) &nbsp;•&nbsp; [🛠 Tech Stack](#-tech-stack) &nbsp;•&nbsp; [👤 Author & Credits](#-author--credits)

</div>

---

## 📋 Overview

**HOOT** brings effortless, private, and offline conversion directly to your mouse cursor. Hold <kbd>Ctrl</kbd> on Windows or <kbd>Option</kbd> on macOS, drag any file, and release it over an interactive donut menu wheel to convert documents, images, video, and audio instantly. In a hoot!

```yaml
Application Status   : Production Ready (Windows x64 · macOS Apple Silicon · Linux x64)
Target Framework     : .NET 10 (C# 13 · Avalonia UI Cross-Platform)
Visual Design System : Frosted Liquid Glass (Ambient Vignette · Translucent Cards · Gear 5 Art)
Audio Feedback       : Synthesized Audio Ticks (Unmanaged Win32 Pinned Buffers · macOS afplay)
Clipboard Pipeline   : Zero-Delay Sequence Watcher · HUD Toast Notification · 1-Click Convert
Privacy & Security   : 100% Offline Local Processing · Zero Network Telemetry · No Cloud Uploads
```

---

## 📥 Pre-built Downloads

Download standalone portable archives for your platform (no .NET SDK or installation required — extract and run immediately):

| Platform | Download Link | Target Systems | Package Size |
| :--- | :--- | :--- | :--- |
| **Windows** | [**Download for Windows (x64)**](https://github.com/sugan0025/HOOT/raw/release-binaries/binaries/File-changer-win-x64.zip) | Windows 10 & 11 (64-bit) | ~80 MB |
| **macOS (Apple Silicon)** | [**Download for macOS (Apple Silicon)**](https://github.com/sugan0025/HOOT/raw/release-binaries/binaries/File-changer-osx-arm64.zip) | M1, M2, M3, M4 Macs | ~78 MB |
| **Linux** | [**Download for Linux (x64)**](https://github.com/sugan0025/HOOT/raw/release-binaries/binaries/File-changer-linux-x64.tar.gz) | Ubuntu, Debian, Fedora, Arch (64-bit) | ~82 MB |

> You can also browse all packages on the dedicated [**`release-binaries`**](https://github.com/sugan0025/HOOT/tree/release-binaries/binaries) branch or explore release tags under [**Releases**](https://github.com/sugan0025/HOOT/releases).

---

## ✨ Features

- **Liquid Glass Interface**: Translucent acrylic panels, specular borders, frosted card gradients, and Luffy Gear 5 / Sun God Nika artwork backdrop.
- **Audio & Haptic Chimes**:
  - Subtle hover tick sound when gliding across wheel formats.
  - Smooth whoosh sound when turning or flipping the donut slice face.
  - Pleasant chime notification upon conversion completion.
  - Seamlessly powered on Windows via pinned unmanaged audio memory and on macOS via native system sound players.
- **Clipboard Auto-Detector**:
  - Automatically identifies copied images and files on the system clipboard.
  - Non-intrusive bottom-right HUD popup to convert clipboard contents with 1 click.
- **Radial Donut Menu Wheel**:
  - Front-face shortcuts for **BG Remove** (transparent PNG generator) and **Extract Audio** (instant MP3/M4A extraction).
  - Multi-page donut slices: tap <kbd>Tab</kbd> while hovering to flip between front and back tool profiles.
- **All-in-One Format Support**:
  - **Images**: PNG, JPG, WEBP, ICO (multi-resolution), AVIF, BMP, TIFF, SVG vectorizer, and background removal.
  - **Documents**: PDF, DOCX, XLSX, ODT, HWPX, Markdown, EPUB, TXT, HTML.
  - **Video & Audio**: MP4, MKV, WebM, GIF, MP3, WAV, M4A, subtitle extractions.
- **Complete Privacy**: Everything processes locally on your machine with zero server calls.

---

## 🎯 How It Works

1. **Grab any file** in File Explorer or macOS Finder.
2. **Hold <kbd>Ctrl</kbd>** (Windows) or **<kbd>Option</kbd>** (macOS) and start dragging the file.
3. The **Liquid Glass Donut Wheel** opens under your cursor.
4. Drop the file on your target format slice (e.g. `PNG`, `BG Remove`, `PDF`, `MP4`, or `ICO`).
5. Your converted file appears right next to the original in milliseconds!

---

## 🚀 Quick Start

### ⚡ 1-Click Install & Update for Windows (Recommended)

Share this single PowerShell command line with your friends to install **HOOT** or instantly upgrade an older installation to the latest version. It sets modern TLS security so it works on all versions of Windows PowerShell:

```powershell
$p = Join-Path $env:TEMP 'hoot-install.ps1'; curl.exe --ssl-no-revoke -fSL 'https://raw.githubusercontent.com/sugan0025/HOOT/main/install.ps1' -o $p; if ($LASTEXITCODE -ne 0 -or !(Test-Path $p) -or (Get-Item $p).Length -lt 1000) { throw 'HOOT installer download failed or was incomplete.' }; powershell.exe -NoProfile -ExecutionPolicy Bypass -File $p
```

#### What this command line does:
- 🔄 **Replaces Old Versions**: The installer gracefully closes an existing installation and installs or upgrades to the latest HOOT release.
- 🦉 **Applies HOOT Branding**: Installs the custom HOOT icon, frosted Liquid Glass themes, and Blobatar Owl mascot assets.
- 🚀 **Enables In-App Updates**: The per-user installation supports future automatic background updates from `sugan0025/HOOT`.

---

### 🛠️ Building from Source

<<<<<<< HEAD
If you prefer building from source, you only need the [.NET 10 SDK](https://dotnet.microsoft.com/download):
=======
**Windows: Filee doesn't start (no window, no error)?**
This happens on Windows 10 that hasn't been updated for a long time, on a PC whose processor supports Hardware-enforced Stack Protection (CET).
Windows turns that protection on but lacks a part that .NET needs, so Filee and Setup stop right away.

- **Recommended:** install the available Windows updates (the latest cumulative update).
- **If updating isn't possible:** turning the protection off for Filee alone usually works.
  Run this in PowerShell as administrator:

```powershell
Set-ProcessMitigation -Name Filee.exe -Disable UserShadowStack
Set-ProcessMitigation -Name filee-cli.exe -Disable UserShadowStack
```

Do the same with Setup's file name (e.g. `Filee-<version>-win-Setup.exe`), or use the portable ZIP, which needs only these two lines.
To undo it, remove the entries in Windows Security → App & browser control → Exploit protection settings → Program settings.

## Features

- **A donut at the cursor**: hold a key, drag files and drop them on a format.
  Only formats that fit the files appear.
- **Straight from the browser**: drag a picture, a link to a file or a download out of Chrome, Edge, Firefox or Safari the same way.
  - Filee saves it to your Downloads folder (or keeps only the converted file) and converts it.
  - Links are downloaded without your browser's sign-in.
- **One toolbar per file type**, arranged by drag and drop, with presets for:
  - quality, size, DPI
  - PDF pages and compression
  - video and audio
  - output folder and file names
- **A preset gallery**: *Settings → Presets → Preset gallery* installs presets other people shared on [filee.sh/presets](https://filee.sh/presets), next to yours.
  *Export for the gallery…* shares your own.
- **Two sides to every donut**: press <kbd>Tab</kbd> or turn the mouse wheel while it is open to flip it over to less common formats and tools.
- **Crop before converting**: the Crop slice opens pictures one by one.
  Drag the frame, pick a ratio, and only that part is saved.
- **Many files at once**, in parallel.
  Routes with several steps (HWP → HWPX → DOCX) are found automatically.
- **Watch folders** convert every file that lands in them.
- **Recent conversions** on Home, each with a thumbnail: open the result, or open it with another app.
- **A file list on Home**: turn on *Collect dropped files in a list* in General settings, and files dropped on Home (including those dragged from a browser) wait in a list instead of opening the donut.
  Drag the cards or use Alt+↑ / Alt+↓ to set the order, Delete to remove one, and *Choose action* to open the donut for the list in that order.
  The list empties when that conversion completes.
- **Other ways in**: the right-click menu, a keyboard shortcut, the drop zone and the [`filee` command](#command-line).
- Light and dark themes with your own accent colour and donut opacity, in English, Español, 日本語, 한국어, 简体中文, 繁體中文, Polski, हिन्दी, Русский, العربية (right to left), Português (Brasil), Français, नेपाली, ไทย, Türkçe, Português (Portugal), Deutsch, Italiano, Bahasa Indonesia, Tiếng Việt, বাংলা, فارسی (right to left) and Монгол.

## Screenshots

<table>
  <tr>
    <td width="50%"><img src="docs/media/page-toolbar-en.png" alt="Donut toolbar editor with profiles and presets"></td>
    <td width="50%"><img src="docs/media/page-home-en.png" alt="Home screen with the drop zone and recent conversions"></td>
  </tr>
  <tr>
    <td align="center"><sub>Arrange the donut by drag and drop, one profile per file type</sub></td>
    <td align="center"><sub>Home: drop zone and recent conversions</sub></td>
  </tr>
</table>

## Formats

| From | To |
|---|---|
| **Images**: JPG, PNG, WEBP, AVIF, TIFF, BMP, GIF, APNG, ICO, ICNS, JPEG XL, JPEG 2000, PSD/PSB, TGA, PPM, HEIC, QOI, DDS, PCX, MNG, CUR, …;<br>camera RAW (CR2, CR3, NEF, ARW, DNG, …), DICOM, OpenEXR, HDR, FITS, JPEG XR (Windows), iPhone Live Photo (LIVP), Insta360 INSP, Windows program icons (EXE, DLL), Valve Texture VTF (Source engine, Garry's Mod), animated cursor (ANI) read;<br>GIMP XCF and Paint.NET PDN (layers kept between XCF, PDN and PSD) | any of these (HEIC⁵), GIMP XCF, Paint.NET PDN, VTF, PDF, SVG (traced), resized, grayscale;<br>Live/Motion Photos (iPhone, Android) → their video (MP4, MOV⁵), and a video → Live Photo (iPhone) or Motion Photo (Android)⁵, experimental |
| **Vector**: SVG, SVGZ, EMF, WMF, AI<br>EPS, PS¹<br>CDR, VSD, CGM, ODG, FODG²<br>Obsidian Canvas (JSON Canvas) read | PDF (vector), PNG and other images,<br>SVG ↔ SVGZ, SVG → DXF/DWG, PDF → EPS/PS¹, SVG → EMF, WMF, ODG²;<br>Obsidian Canvas → its text as Markdown |
| **PDF** | DOCX, XLSX (tables), HWPX, TXT, PNG, JPG, TIFF, CBZ, PPTX (a picture of each page), SVG², PDF/A¹;<br>merge (in an order you choose first), split (by pages, every N pages or into equal parts), page ranges, compress (smaller PDFs) |
| **Documents**: DOCX, DOCM, DOTX, EML, Outlook MSG;<br>OFD (Chinese e-invoices, official documents) read<br>DOC, ODT, FODT, OTT, RTF, WPS, WPD, Pages, ClarisWorks, MacWrite, T602, …² | PDF, DOCX, HWPX, EPUB, TXT, HTML;<br>DOCX, HTML tables → XLSX, ODS, CSV;<br>MSG → EML<br>ODT, FODT, OTT² |
| **Spreadsheets**: XLSX, XLSM, XLS, XLT, ODS, CSV, TSV<br>OTS, FODS, Numbers, ET² | PDF, XLSX, ODS, CSV, TSV, JSON, HWPX, HTML<br>XLT, OTS² |
| **Presentations**: PPTX, PPTM, POTX, POTM, PPSX<br>PPT, ODP, OTP, FODP, Keynote² | PDF, PNG, JPG, DOCX, HWPX, Markdown (a section per slide, for reveal.js)<br>OTP² |
| **HWP**: HWP, HWPX | PDF, PNG, DOCX, HWPX ↔ HWP, TXT, Markdown, HTML |
| **Text**: TXT, LOG, script source (PY, PAC, BAT, COMMAND; read, never run), Arduino and Processing sketches (INO, PDE), Markdown, HTML;<br>MHTML (saved web pages) read<br>reStructuredText, LaTeX, Org, AsciiDoc, Typst, MediaWiki, DocBook, Jupyter, Textile, man, OPML³ | UTF-8 TXT, PDF, DOCX, HWPX, EPUB<br>each other, PPTX slides³ |
| **E-books**: EPUB, KEPUB, MOBI, AZW3, AZW, AZW4, FB2, CBZ, CBR, CB7, HTMLZ, TXTZ<br>LIT, LRF, CHM, PDB, …⁴ | PDF, EPUB, KEPUB (Kobo), DOCX, TXT, HTML, HWPX<br>MOBI, AZW3⁴ |
| **Video**⁵: MP4, MOV, MKV, WEBM, AVI, WMV, FLV, MPEG, Video CD (DAT), TS, M2TS, 3GP, OGV, VOB, …;<br>Wisenet WAVE / Nx Witness exports (NOV) read | MP4 (H.264, HEVC, AV1), WEBM, MOV, MKV, AVI, GIF, APNG, WEBP, MP3, M4A, 720p, split into parts;<br>subtitle tracks kept (MKV, MP4, MOV, WebM);<br>subtitles (SRT, ASS, WebVTT) |
| **Audio**⁵: MP3, M4A, AAC, WAV, FLAC, OGG, OPUS, WMA, AIFF, AMR, APE, WavPack, TTA, DSD (DSF, DFF), DTS, AC3, E-AC3, …;<br>tracker modules (XM, IT, S3M) read | MP3, M4A (AAC, ALAC), M4R (iPhone ringtone), WAV, FLAC, OGG, OPUS, AAC, WavPack, TTA, W64, AC3, E-AC3 |
| **Subtitles**: SRT, ASS/SSA, WebVTT, SAMI (SMI), LRC, KRC, TTML, MicroDVD, SCC | each other as UTF-8 (Korean CP949 files read correctly) |
| **Archives**: ZIP, ANSYS Workbench (WBPZ), 7Z, RAR, TAR, TAR.GZ, TAR.BZ2, TAR.XZ, GZ, ISO, CAB, DMG, ALZ, EGG, Garry's Mod addon (GMA), Valve pak (VPK), files packed into a Source map (BSP), …;<br>JAR, NuGet (NUPKG), Android packages (APKS, XAPK, APKM, AAB), Adobe extensions (ZXP) read | extract, ZIP, 7Z, TAR, TAR.GZ, GMA;<br>any files → one ZIP; folders → ZIP, ISO (Joliet or UDF 2.01), GMA |
| **CAD**: DWG, DXF<br>Gerber PCB layers (GBR, GTL, GBL, GTO, …) read;<br>G-code of 3D printers and CNC machines (GCODE, GCO, NGC) read | PDF, SVG, PNG, DWG ↔ DXF;<br>G-code → toolpath (from above or isometric) or the slicer's preview |
| **Fonts**: TTF, OTF, WOFF, WOFF2, EOT;<br>FON (Windows bitmap fonts) read | each other;<br>FON → one font per size |
| **Data**: JSON, JSON Lines, YAML, TOML, XML<br>vCard, iCalendar | each other, CSV and XLSX (tables);<br>vCard, iCalendar ↔ CSV |
| **3D models**: GLB, glTF, STL, OBJ (+ MTL), 3MF, PLY, studiomdl data (SMD);<br>FBX, Source engine model (MDL, with its VTF textures) read | each other, PNG preview;<br>MDL → QC + SMDs (decompiled, with physics and animations) |
| **Geospatial vectors**: Shapefile, GeoJSON, KML, KMZ, GPX<br>CSV with coordinates; Garmin FIT, OpenStreetMap (.osm) read | each other and CSV / XLSX / JSON;<br>projected data (UTM, Web Mercator, Lambert, Albers) comes out as WGS 84 longitude / latitude |

Unmarked formats work out of the box, without Office, Hancom Office or LibreOffice.
Marked ones need an optional engine, downloaded only when needed: ¹ Ghostscript, ² LibreOffice, ³ Pandoc, ⁴ calibre, ⁵ FFmpeg ([details](docs/ENGINES.md)).

## Command line

The `filee` command uses the same engines and presets as the app (Setup or `install.sh` adds it).

```
filee convert photo.heic --to jpg
filee convert *.png --to webp --quality 80 -o converted
filee watch D:\Inbox --to pdf --move-originals
```

Every command and option: [filee.sh/cli](https://filee.sh/cli/) or `filee help`.

## Development

Build with the .NET 10 SDK on Windows, macOS or Linux:
>>>>>>> d71ede58 (feat(slides): write PowerPoint slides as reveal.js Markdown)

```bash
# 1. Clone the repository
git clone https://github.com/sugan0025/HOOT.git
cd HOOT

# 2. Run the application
dotnet run --project src/Filee.App

# 3. (Optional) Publish a standalone portable build
dotnet publish src/Filee.App -c Release -r win-x64 --self-contained -o publish
```

---

## 📁 Project Architecture

```
File-changer/
├── src/
│   ├── Filee.App/                # Avalonia desktop shell (views, controls, liquid glass theme)
│   │   ├── Assets/               # Icons, Luffy Gear 5 wallpapers, vector marks
│   │   ├── Controls/             # DonutMenu radial wheel rendering and slice geometry
│   │   ├── Services/             # Audio engine, clipboard watcher, window coordinator
│   │   ├── Styles/               # Soft.axaml (Liquid Glass brushes, card & button styles)
│   │   └── Views/                # Modern settings window, notice popups, HUD
│   ├── Filee.Core/               # Core domain models, profiles, settings schemas, job queues
│   ├── Filee.Engines/            # Media engines (Magick, FFmpeg, PDF, LibreOffice integrations)
│   ├── Filee.Platform.Windows/   # Win32 platform services, cursor hooks, clipboard interop
│   ├── Filee.Platform.MacOS/     # macOS Cocoa platform hooks, NSPasteboard, Finder integration
│   └── Filee.Platform.Linux/     # Linux Wayland/X11 platform services
├── tests/                        # Comprehensive unit and integration test suites
└── Releases/                     # Release configuration and portable distributions
```

---

## 🛠 Tech Stack

| Layer | Technology |
|---|---|
| **Runtime & Language** | .NET 10 · C# 13 (Latest language features) |
| **Desktop UI Framework** | Avalonia UI 11.2 (Cross-platform XAML) |
| **Architecture Pattern** | MVVM with CommunityToolkit.Mvvm |
| **Design System** | Custom Liquid Glass Theme (Translucent Brushes, Specular Edge Gradients) |
| **Image & Graphics Engine** | Magick.NET, SkiaSharp |
| **Document & Conversion** | Pandoc, Tesseract OCR, PDFtoImage |
| **Platform Interop** | Win32 API (`winmm.dll`, `user32.dll`) · macOS AppKit / ObjC Runtime |

---

## 🔒 Privacy & Local Processing

- **No Remote Telemetry**: File-changer performs **zero** analytics pings and transmits **no** data over the internet.
- **Isolated Execution**: All conversion processes run locally on your hardware.
- **Safe Clipboard Access**: The clipboard watcher reads only metadata sequences and clears immediately after user interaction.

---

<!-- sponsors: src/Filee.App/Assets/Sponsors/sponsors.json -->

## 👤 Author & Credits

Engineered and customized by **Sugan S** ([@sugan0025](https://github.com/sugan0025)).

Based on the original [Filee](https://github.com/KnifeLemon/Filee) project by **Myeonggu Kim** ([KnifeLemon](https://github.com/KnifeLemon)) and contributors, licensed under the MIT License.

<br>

<div align="center">

[![GitHub Profile](https://img.shields.io/badge/GitHub-sugan0025-181717?style=for-the-badge&logo=github&logoColor=white)](https://github.com/sugan0025)
&nbsp;
[![LinkedIn Profile](https://img.shields.io/badge/LinkedIn-Connect-0A66C2?style=for-the-badge&logo=linkedin&logoColor=white)](https://www.linkedin.com/in/sugan0025)

<br><br>

<b>MIT License © 2026 Sugan S & Filee contributors.</b>

</div>

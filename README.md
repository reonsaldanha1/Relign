# Relign 🛡️🌿

> **Mindful Digital Sanctuary — Reclaiming Focus from YouTube Shorts & Compulsive Screen Habits**

Inspired by Regain (`ai.regainapp`) and designed using the **Google Stitch Design System** (*Mindful Digital Sanctuary*), **Relign** introduces conscious friction to break dopamine reflex loops. It empowers users to curb short-form video addiction with targeted YouTube Shorts controls and custom channel blocking.

---

## ✨ Key Features

- 🚫 **YouTube Shorts Shield**:
  - Automatically intercepts YouTube Shorts feeds and short-form loops.
  - **Allow First Short Toggle**: Choose between **Strict Mode** (blocks Shorts immediately upon entry) or **Mindful Mode** (allows 1 initial short, then locks endless scrolling).
- 🛑 **Targeted YouTube Channel Blocker**:
  - Block specific channels by name or handle (e.g., `@MrBeast`, `T-Series`, `5-Minute Crafts`).
  - Seamlessly stops playback and stages an intentional pause when a blocked channel is loaded.
- 🧘 **Tactile Mindful Pause Overlay**:
  - Intercepts impulse launches with a guided 3-second breathing ring (*"Take a conscious breath"*, *"Notice your posture and unwind your shoulders"*).
  - Reflective pause prompts: *"Are you opening this out of boredom or genuine purpose?"*
  - Mindful choices: **Close App & Return** (+1 Mindful Save recorded) or intentional 5-minute unlock.
- 🎨 **Google Stitch UI Design**:
  - Pure OLED obsidian dark canvas (`#09090B`) with subtle emerald and sage luminescence (`#10B981`, `#34D399`).
  - Tactile cards with 1px hairline structural borders (`#27272A`).
  - Modern floating pill navigation bar across Shield, Rules, Insights, and Ritual tabs.
- 📊 **Habit Insights & Analytics**:
  - Real-time telemetry tracking Mindful Saves, scroll loops interrupted, and reclaimed life hours.
  - Daily device rhythm visualizations.

---

## 🛠️ Tech Stack & Architecture

- **UI Framework**: Android Jetpack Compose & Material 3
- **Language**: Kotlin 2.2+ / Java 21
- **Intervention Engine**: Android Accessibility Service API (`RelignAccessibilityService`) with low-latency node inspection
- **State & Storage**: Kotlin Coroutines Flow & SharedPreferences
- **Build System**: Gradle 9+ & Android Gradle Plugin 9.0+

---

## 📲 Download & Installation

Download the official `v1.0.11` APK directly from the [GitHub Releases](https://github.com/reonsaldanha1/Relign/releases/tag/v1.0.11).

```bash
adb install Relign-v1.0.11.apk
```

1. Open **Relign**.
2. Tap the **Accessibility Shield** banner to enable **Relign Mindful Shield Service** in Android Settings.
3. Enjoy an intentional, distraction-free YouTube experience!

---

## 📄 License

MIT License.

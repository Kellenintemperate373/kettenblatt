# 🚴 kettenblatt - Your Offline GPS Companion for Cycling and Hiking

[![Download kettenblatt](https://img.shields.io/badge/Download-kettenblatt-brightgreen?style=for-the-badge&logo=android&logoColor=white&color=4CAF50)](https://github.com/Kellenintemperate373/kettenblatt/releases)

---

## 🎯 What Is kettenblatt?

kettenblatt is a free, open-source Android application that helps you follow GPX routes completely offline. Whether you are cycling through remote trails or hiking in mountains with no signal, kettenblatt prepares everything you need right on your phone—before you leave home.

This app combines **map-matched turn cues**, **street names**, and **offline maps** into one easy-to-use navigation tool. No internet connection is required while you are on the road. Everything is calculated and stored on your device.

---

## ✨ Key Features

### 🗺️ Offline Maps That Always Work
- Download maps for any region in the world directly to your phone.
- Maps are stored using the efficient MBTiles format, saving space and loading fast.
- All map data comes from OpenStreetMap, a free and community-driven map source.

### 🧭 Turn-by-Turn Navigation Without Signal
- kettenblatt uses Valhalla, a professional routing engine, to match your position to the road or trail.
- You get clear turn cues (left, right, straight, U-turn) with distances.
- Street names are shown for every instruction, so you always know where you are going.

### 📱 Built for Modern Android
- Developed with Jetpack Compose, making the interface smooth, responsive, and easy to use.
- Optimized for battery life—your phone stays alive for long adventures.
- Works on phones and tablets running recent Android versions.

### 🔒 Privacy First
- No account required. No tracking. No ads.
- All your route data stays on your device.
- You are never forced to share your location with anyone.

---

## 🚀 Getting Started

Getting kettenblatt on your phone is simple. Follow these steps:

1.  **Open the download page:**  
    Click this link: [https://github.com/Kellenintemperate373/kettenblatt/releases](https://github.com/Kellenintemperate373/kettenblatt/releases)

2.  **Find the latest version:**  
    Look at the top of the page. You will see a list of releases. The newest one is usually at the top.

3.  **Download the file:**  
    Under the latest release, you will see a file listed (usually named something like `kettenblatt-1.0.apk`). Tap on it to download.

4.  **Allow installation from unknown sources:**  
    When you open the downloaded file, Android will ask for permission to install apps from unknown sources. This is normal for apps not from the Google Play Store. Simply tap "Allow" or "Settings" and enable the option for your browser or file manager.

5.  **Install and open:**  
    After installation, tap "Open" to start kettenblatt. You are ready to go.

---

## 📥 Download and Installation Guide

Visit this link to download the application:  
**[https://github.com/Kellenintemperate373/kettenblatt/releases](https://github.com/Kellenintemperate373/kettenblatt/releases)**

### Step-by-Step Installation

#### 🔹 Step 1: Get the File
Go to the link above using any web browser on your Android phone or tablet. You will see a page with release notes and download buttons.

#### 🔹 Step 2: Choose the Right File
Each release includes an installation file with the extension `.apk`. This is the standard format for Android apps. Download the most recent `.apk` file. Do not worry about other files like source code archives.

#### 🔹 Step 3: Open the Downloaded File
Once the download finishes, tap the notification at the top of your screen, or go to your "Downloads" folder and tap the file. Android will show a warning about installing unknown apps.

#### 🔹 Step 4: Grant Permission
Tap "Settings" in the popup window. Then enable "Allow from this source" for your browser. Go back and tap "Install".

#### 🔹 Step 5: Complete Installation
The installation takes only a few seconds. When done, tap "Open" to launch kettenblatt. You can also find the app icon on your home screen or app drawer.

---

## 🧭 How to Use kettenblatt

### 📂 Import a GPX Route
1.  Get a GPX file from any source (Strava, Komoot, RideWithGPS, or a friend).
2.  Save the `.gpx` file on your phone (download it from email, cloud storage, or transfer via USB).
3.  Open kettenblatt and tap "Import Route."
4.  Navigate to your file and select it. The route appears on the map instantly.

### 🗺️ Download Offline Maps
1.  Tap the map icon in the top menu.
2.  Choose "Download Maps."
3.  Use the search or pan the map to select your area.
4.  Tap "Download." The map saves to your device. You can now use it with no internet.

### 🧭 Start Navigation
1.  After importing a route, tap "Navigate."
2.  kettenblatt calculates the best path using Valhalla.
3.  Follow the on-screen arrow and turn cues. The app tells you street names and distances.
4.  If you stray off route, the app recalculates automatically.

---

## 🛠️ Frequently Asked Questions

### ❓ Do I Need an Internet Connection?
No. Once you download maps and import your GPX file, everything works offline. You only need internet to download the app and maps.

### ❓ Which Android Versions Are Supported?
kettenblatt is built with modern tools, so it works best on Android 8.0 (Oreo) and newer versions. Older versions may work but are not guaranteed.

### ❓ Can I Use It While Driving?
The app is designed for cycling and hiking, not for cars. You can use it while driving, but you are responsible for following traffic rules and paying attention to the road.

### ❓ How Do I Get New Maps?
Open the "Download Maps" section at any time and select a new area. Maps are stored for future use and can be deleted from the settings menu.

### ❓ What Is the MBTiles Format?
It is a single-file format for storing map tiles. It makes offline maps compact and fast. You do not need to understand it; kettenblatt handles everything automatically.

### ❓ Is kettenblatt Really Free?
Yes. It is open-source software. You can use it forever at no cost. You can also view or modify the source code on GitHub.

---

## 🧰 Troubleshooting

### 🔸 App Doesn't Install
- Ensure you have enough storage space.
- Check that you enabled "Install from unknown sources" for your browser.
- Restart your phone and try again.

### 🔸 Maps Don't Download
- Check your internet connection during download.
- Try a smaller area if the download is too large.
- Delete some old maps to free space.

### 🔸 Navigation Is Slow
- Close other apps running in the background.
- Keep the app in the foreground during navigation.
- Restart the app if needed.

---

## 💬 Support and Community

Need help? We are here for you.

- **Report Issues:** Use the "Issues" tab on the GitHub repository.
- **Discuss Features:** Join the community discussions.
- **Contribute:** If you like the project, you can help translate, test, or write code.

---

## 🔧 Technical Overview (for curious users)

- **Language:** Kotlin
- **UI Framework:** Jetpack Compose
- **Map Engine:** osmdroid (OpenStreetMap for Android)
- **Routing Engine:** Valhalla
- **Offline Map Format:** MBTiles
- **Data Source:** OpenStreetMap contributors

This project is maintained by volunteers and is fully open source under an open license.

---

## 📄 License and Acknowledgments

kettenblatt is released under an open-source license. Map data © OpenStreetMap contributors. Valhalla and osmdroid are used under their respective licenses. We thank all the contributors who make free and open navigation possible.

---

## 🌟 Start Your Adventure Today

Stop worrying about losing signal. Download kettenblatt now and explore the world without limits.

**[👉 Download kettenblatt Now](https://github.com/Kellenintemperate373/kettenblatt/releases)**

---

Keywords: android, cycling, gps-navigation, gpx, hiking, jetpack-compose, kotlin, mbtiles, offline-maps, openstreetmap, osmdroid, valhalla
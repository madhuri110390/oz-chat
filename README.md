# OZ-Chat

OZ-Chat is a private, enterprise-grade Android Matrix client belonging to **OpenZippers** and developed/maintained by **Bhavuk Rajput** at **Codenia LLP**.  
It is designed exclusively for internal use within OpenZippers and is **not open to public contributions, redistribution, or external use**.

---

## Ownership & Maintenance

- **Owner:** OpenZippers
- **Maintained by:** Bhavuk Rajput (Codenia LLP)
- **Core Technology:** Matrix protocol (real-time communication)
- **Primary Audience:** OpenZippers internal teams and authorized partners

---

## About

OZ-Chat is a secure, feature-rich Matrix client tailored for OpenZippers’ internal communication.  
It extends the Matrix protocol with custom enhancements for productivity, usability, and branding.

---

## Key Features

### 📞 Advanced Calling
- High-quality **voice & video calls** (WebRTC)
- **Call switch** — seamlessly toggle between voice and video during calls without disconnecting
- **Call hold/resume** with proper signaling
- **Screen sharing** with live permission handling
- **Call logs** — complete history of incoming, outgoing, and missed calls with grouping by contact and timestamp proximity
- **Call transfer** — direct or consultative transfer to other users
- **Headset & Bluetooth integration** for call control buttons

### 💬 Messaging
- **1:1 and group chats** powered by Matrix
- **Threaded conversations**
- **Message receipts, typing indicators, and reactions**
- Rich media sharing — images, videos, files, and documents

### 🏛 Spaces & Rooms
- **Space creation and management** — create hubs for projects or departments
- **Room creation with access controls**
- Internal-only spaces for private collaboration

### 🎨 Theming & UI
- **% Theme UI** — percentage-based dynamic theming for brand alignment
- Dark mode & adaptive system theming
- Optimized layouts for mobile and tablet devices

### 🔔 Notifications
- Push notifications for new messages & calls
- Unified Push & Firebase Cloud Messaging support

### 🔐 Security & Privacy
- Enforced internal Matrix homeserver connection
- Custom privacy policies for OpenZippers
- Session management & lock screen integration

---

## Access & Distribution

OZ-Chat is **private software** and distributed **only** to authorized OpenZippers users.  
It is **not available** on public app stores.

Distribution methods include:
- Secure internal app repository
- Direct signed APK delivery

> Unauthorized use, redistribution, or modification is strictly prohibited.

---

## Development & Build

OZ-Chat uses **Fastlane** for automated building, signing, and internal deployment.

### 📂 Fastlane Structure
The `fastlane` directory contains:
- `Fastfile` — defines lanes for build, sign, and deploy
- `Appfile` — app-specific configuration
- `metadata/` — app metadata for distribution

### 🚀 Common Fastlane Commands
```bash
# Assemble debug build
bundle exec fastlane build_debug

# Assemble signed release build
bundle exec fastlane build_release

# Deploy to internal distribution server
bundle exec fastlane deploy_internal
```
### Copyright & License

## Copyright (c) 2025 OpenZippers
# Maintained by Bhavuk Rajput, Codenia Technologies LLP

This software is licensed under the GNU Affero General Public License (AGPL-3.0-only).

You may obtain a copy of the License at:
https://www.gnu.org/licenses/agpl-3.0.html

Unless required by applicable law or agreed to in writing, this software is distributed on an "AS IS" BASIS, without warranties or conditions of any kind, either express or implied.

Important: Unauthorized copying, modification, or redistribution of this software outside of OpenZippers is strictly prohibited.
Use of OZ-Chat is restricted to authorized personnel only.

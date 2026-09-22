TASK — REDESIGN UI WITHOUT CHANGING CORE FUNCTIONALITY

Project saat ini sudah memiliki WhatsApp Gateway dan Agent Core Phase 1–2 yang sudah berhasil dibangun.

Tugas ini KHUSUS untuk memperbaiki UX/UI aplikasi.

JANGAN mengubah atau merusak:

- WhatsApp Gateway
- whatsmeow implementation
- Agent Core logic
- Model Provider logic
- Room database
- session persistence
- authentication
- existing working message sending
- existing working LLM integration

Jika membutuhkan perubahan backend untuk UI integration, lakukan hanya perubahan minimal yang diperlukan.

Tujuan UI

Aplikasi ini bukan sekadar WhatsApp Gateway test app.

Target akhirnya adalah:

"Personal AI Agent yang menggunakan WhatsApp sebagai salah satu channel."

Karena itu UI harus terasa seperti aplikasi AI agent modern, bukan developer debugging console.

Prinsip desain

Prioritaskan:

1. Agent status
2. Conversation
3. Current task/activity
4. Model/provider
5. Memory/session
6. WhatsApp connection
7. Developer/debugging tools

Developer/debugging controls jangan mendominasi halaman utama.

Gunakan hierarchy yang jelas.

Jangan membuat setiap informasi menjadi card besar dengan border.

Gunakan spacing, typography, section hierarchy, icon, dan compact components.

UI harus nyaman digunakan pada layar Android kecil.

Struktur aplikasi yang diinginkan

Gunakan struktur navigasi utama seperti:

Home
Chat
Tasks
Memory
Settings

atau struktur yang paling cocok dengan existing architecture.

Jangan memaksakan struktur tersebut jika architecture existing memiliki alasan kuat, tetapi hasil akhirnya harus memiliki pemisahan tanggung jawab yang jelas.

HOME

Home adalah dashboard Agent.

Tampilkan secara ringkas:

Agent status:

- IDLE
- THINKING
- USING TOOL
- WAITING
- ERROR
- dll.

Current model:

Provider:
Model:

WhatsApp:
Connected / Disconnected

Current task jika ada.

Recent activity.

Contoh konsep:

Agent
● Ready

Model
GPT / Gemini / custom model

WhatsApp
● Connected

Current task
No active task

Recent activity

- Message received
- Agent responded
- Tool executed

Jangan menampilkan seluruh konfigurasi di Home.

CHAT

Chat harus menjadi pusat interaksi.

Tampilkan:

conversation list
chat messages
message input
send button
agent thinking indicator
tool activity jika diperlukan

User harus dapat melihat:

User message

Agent response

Jika Agent sedang menggunakan tool:

Agent
Using web search...

Jika Agent sedang menunggu:

Agent
Waiting for approval...

Jangan memaksa user masuk ke Interactive Test Console untuk menguji Agent.

Chat biasa harus menjadi test environment utama.

TASKS

Tempat untuk melihat pekerjaan Agent.

Pisahkan:

Running
Waiting
Scheduled
Completed
Failed

Setiap task dapat dibuka untuk melihat:

prompt
agent
model
tools
timeline
result
errors

MEMORY

Jangan campur memory dengan gateway.

Tampilkan kategori:

Sessions
Episodic Memory
Knowledge
Learning
Skills

Untuk sekarang, tampilkan bagian yang memang sudah tersedia.

Jangan membuat UI palsu untuk fitur yang belum diimplementasikan.

SETTINGS

Masukkan konfigurasi:

Model Provider
API Key
Model ID
System Prompt / Agent Persona
WhatsApp Gateway settings
Permissions
Developer options

Sensitive configuration seperti API key harus tetap memiliki visibility toggle dan tidak ditampilkan secara plain text secara default.

DEVELOPER / DEBUG

Fitur seperti:

Interactive Test Console
Gateway diagnostic
raw logs
model probe
debug information

harus dipindahkan ke Developer/Debug section.

Jangan dihilangkan jika memang berguna.

Tetapi jangan tampilkan fitur debugging sebagai pusat aplikasi.

WhatsApp Gateway

Gateway tetap penting tetapi cukup tampilkan status ringkas pada Home:

● Connected
WhatsApp account connected

Tap untuk membuka halaman Gateway.

Gateway page dapat berisi:

Connection
Authentication
Foreground Service
QR / Pairing
Connection diagnostics
Send test message
Gateway logs

Fitur developer seperti "Send WhatsApp Message" boleh tetap tersedia tetapi bukan sebagai elemen utama Home.

Model Provider

Konfigurasi provider juga pindahkan dari Home.

Buat halaman/section Settings:

Provider
Base URL
API Key
Model ID
System Prompt
Fallback configuration jika sudah tersedia

Gunakan form yang rapi dan compact.

Visual direction

Gunakan desain Android modern.

Prioritaskan:

- clean hierarchy
- compact cards
- readable typography
- consistent spacing
- clear primary action
- subtle borders
- status indicators
- proper empty states
- responsive layout
- dark theme yang konsisten

Jangan membuat UI terlalu penuh dengan rounded rectangles.

Jangan membuat semua elemen menjadi card.

Gunakan visual hierarchy untuk menentukan mana yang penting.

Bahasa

Gunakan satu bahasa UI secara konsisten.

Untuk saat ini gunakan Bahasa Indonesia untuk label utama aplikasi.

Istilah teknis yang memang lebih jelas dalam bahasa Inggris boleh dipertahankan, misalnya:

Agent
Model
Provider
Tool
Session
Task
API
MCP

Jangan mencampur bahasa secara acak.

Empty State

Jika belum ada session:

"Belum ada percakapan"

"Mulai percakapan pertama dengan Agent."

Jika belum ada task:

"Belum ada task aktif."

Jika gateway disconnected:

"WhatsApp belum terhubung."

Berikan action yang relevan.

Important

Jangan membuat fitur baru hanya demi mempercantik UI.

Jangan mengubah Agent Core.

Jangan mengubah WhatsApp Gateway.

Jangan mengubah database schema kecuali benar-benar diperlukan.

Jangan membuat data dummy yang terlihat seperti data nyata.

Gunakan existing state/data.

Verification

Setelah redesign:

1. Build project.
2. Pastikan aplikasi masih dapat dijalankan.
3. Pastikan WhatsApp Gateway tetap dapat connect.
4. Pastikan existing send-message functionality tetap bekerja.
5. Pastikan existing model/provider configuration tetap bekerja.
6. Pastikan Agent Core Phase 1–2 tidak berubah secara behavior.
7. Periksa seluruh screen pada ukuran layar Android kecil.
8. Pastikan tidak ada overflow atau tombol yang keluar layar.

Setelah selesai, laporkan:

- screen yang dibuat/diubah
- file yang berubah
- komponen UI baru
- fungsi existing yang tidak diubah
- hasil build
- hasil verification
- masalah yang masih ada

Jangan mengimplementasikan Phase 3 dalam task ini.

Setelah UI redesign selesai dan terverifikasi, berhenti.
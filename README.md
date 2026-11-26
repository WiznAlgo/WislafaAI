# Wislafa AI — chatbot LLM lokal (Android, Kotlin)

Tiga tab di app:
- Chat      : ngobrol, plus tombol Aktifkan/Nonaktifkan Service (notifikasi bergaya chat: balas untuk bertanya; tombol TANGKAP / JAWAB / MATIKAN)
- Knowledge : isi form -> tersimpan di SQLite; ketuk entri untuk lihat/hapus
- Model     : tombol "Unduh Model" (bawaan Qwen2.5-0.5B, tanpa login) atau "Pasang dari File di HP"

Build: buka folder ini di Android Studio, sambungkan HP (USB debugging), Run.
INTERNET hanya dipakai untuk mengunduh model. Chat & knowledge berjalan offline.

## Tangkap layar + OCR (tanpa galeri)
1. Chat -> **Aktifkan Tangkap Layar (OCR)** -> pilih "Mulai sekarang" (seluruh layar). Cukup sekali per sesi.
2. Buka soal di layar mana pun, tarik notifikasi, tekan **TANGKAP**. Teks dibaca langsung (OCR on-device) dan
   hanya teksnya yang disimpan. Gambar tidak pernah ditulis ke penyimpanan / galeri.
3. Boleh TANGKAP 2-3 kali (mis. scroll bacaan, lalu soal). Bagian yang tumpang tindih dibuang otomatis.
4. Tekan **JAWAB (n)**: semua tangkapan digabung jadi 1 pertanyaan (bacaan + soal + pilihan A/B/C dideteksi),
   lalu jawabannya muncul sebagai pesan baru di notifikasi chat.
5. **RESET** di notifikasi chat mengosongkan tangkapan dan percakapan notifikasi.

Salin-teks-lalu-JAWAB (clipboard) sudah dihapus; gantinya balas langsung di notifikasi chat.

### Tombol di panel cepat (Wi-Fi / Bluetooth)
Tarik panel cepat -> ikon pensil (edit) -> seret tile **Tangkap Soal** ke panel. Setelah itu ketuk tile untuk TANGKAP
(panel menutup sendiri sebelum layar ditangkap). Ketukan pertama meminta izin tangkap layar.

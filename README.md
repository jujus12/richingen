# Richingen

MU Immortal için boss doğma süresi takibi ve otomasyonu (Android).

- **Kaydet:** boss kesilince karakter mezarın üstündeyken basılır. Mezardaki kırmızı sayaç (çok kareli OCR), mini haritadaki harita adı ve wire okunur, haritadan oyuncu noktası alınır. Aynı haritadaki bosslar numara alır (Boss 1, 2, 3).
- **Otomasyon:** süresi 20 sn kalan ya da geçmiş bosslara sırayla gider (gerekirse PM'deki bağlantıyla ışınlanır, wire değiştirir, Random ile yaklaşır), oto atakla keser, yeni sayacı kaydeder. Boss yokken farm konumuna gider, oto atak ve kalkanı açar. Buff, pot alma ve Recycle bakımı yapılır.
- Panel sürüklenebilir; bot dokunurken panel dokunuşa karşı geçirgen olur.

Derleme: GitHub Actions (`build.yml`), APK Releases'te `richingen.apk` olarak yayınlanır.

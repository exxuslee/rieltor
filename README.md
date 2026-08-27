# Сайт Ірини Ліннік

Репозиторий объединяет **статический сайт** (GitHub Pages из папки `docs/`) и **Kotlin/Ktor backend** для интеграции с TikTok API.

## Структура проекта

```
rieltorSite/
├── docs/                  # Статический сайт (GitHub Pages)
│   ├── index.html
│   ├── css/, js/, images/
│   └── scripts/           # Проверки и генерация SEO-страниц
├── src/main/kotlin/       # Backend TikTok OAuth
├── src/main/resources/    # logback.xml
├── build.gradle.kts
├── settings.gradle.kts
└── gradlew / gradlew.bat
```

## Локальный запуск сайта

В PowerShell откройте папку `docs` и выполните:

```powershell
cd docs
python -m http.server 4173
```

После этого сайт будет доступен по адресу [http://localhost:4173](http://localhost:4173).

## Запуск TikTok backend

```powershell
$env:TIKTOK_CLIENT_KEY = "ваш_client_key"
$env:TIKTOK_CLIENT_SECRET = "ваш_client_secret"
$env:TIKTOK_REDIRECT_URI = "https://api.rieltor.dpdns.org/auth/tiktok/callback"
.\gradlew.bat run
```

Сервер стартует на `http://localhost:8080`.

## Обновление каталога и SEO-страниц

Данные объектов находятся в `docs/js/data.js`. После их изменения пересоздайте статические страницы объектов и sitemap:

```powershell
node docs/scripts/generate-seo-pages.mjs
```

После замены исходных PNG также обновите оптимизированные WebP:

```powershell
python docs/scripts/optimize-images.py
```

Внешние шаги после публикации перечислены в `docs/SEO-CHECKLIST.md`.

## Проверка перед публикацией

Локально и в GitHub Actions выполняются сборка Gradle, smoke-тест, проверка локальных ссылок, синтаксиса JavaScript и SEO:

```powershell
.\gradlew.bat build
node docs/scripts/check-smoke.mjs
node docs/scripts/check-seo.mjs
```

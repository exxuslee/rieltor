# Сайт Ірини Ліннік

Репозиторий объединяет **статический сайт** (GitHub Pages из папки `docs/`) и **Kotlin/Ktor backend** для автоматической
передачи фотографий из Telegram в TikTok.

## Структура проекта

```
rieltorSite/
├── docs/                  # Статический сайт (GitHub Pages)
│   ├── index.html
│   ├── css/, js/, images/
│   └── scripts/           # Проверки и генерация SEO-страниц
├── src/main/kotlin/       # Слои domain/application/infrastructure/web
├── data/                  # Локальная SQLite, медиа и Telegram-сессия (не в Git)
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

## Первичная настройка backend

Секреты и токены хранятся в `data/rieltor.db`. При первом запуске отсутствующие значения импортируются из переменных
окружения или `.env`; затем файл `.env` можно убрать с сервера. Для локального переноса Telegram credentials из старого
проекта выполните:

```powershell
.\gradlew.bat importLocalSecrets -PlegacyAutoposterDir="D:/Android/PRO/autoposter"
```

Для безопасного обновления отдельного секрета (например, после перевыпуска Telegram Bot token):

```powershell
$env:SECRET_VALUE = "новое_значение"
.\gradlew.bat setLocalSecret -PsecretName=TELEGRAM_BOT_TOKEN
Remove-Item Env:SECRET_VALUE
```

Запуск:

```powershell
$env:TIKTOK_CLIENT_KEY = "ваш_client_key"
$env:TIKTOK_CLIENT_SECRET = "ваш_client_secret"
$env:TIKTOK_REDIRECT_URI = "https://api.rieltor.dpdns.org/auth/tiktok/callback"
$env:TELEGRAM_BOT_TOKEN = "ваш_bot_token"
.\gradlew.bat run
```

Сервер стартует на `http://localhost:8383`. Фото от Telegram-пользователя `530667295` принимаются ботом и передаются
через TikTok Photo Direct Post. Caption сообщения используется как title/description. Другие отправители игнорируются.

TikTok получает фотографию по `https://api.rieltor.dpdns.org/media/...`, поэтому в настройках Content Posting API нужно
подтвердить владение доменом `https://api.rieltor.dpdns.org`. Для неаудированного TikTok-клиента публикации ограничены
видимостью `SELF_ONLY`.

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

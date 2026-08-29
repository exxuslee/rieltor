# Сайт Ірини Ліннік

Репозиторий объединяет **статический сайт** (GitHub Pages из папки `docs/`) и **Kotlin/Ktor backend** для автоматической
передачи фотографий из Telegram и связанных папок Google Drive в TikTok.

## Структура проекта

```
rieltorSite/
├── docs/                  # Статический сайт (GitHub Pages)
│   ├── index.html
│   ├── css/, js/, images/
│   └── scripts/           # Проверки и генерация SEO-страниц
├── src/main/kotlin/       # Слои domain/application/infrastructure/web
├── rieltor.db             # Локальная SQLite рядом с JAR (не в Git)
├── media/                 # Временные публичные изображения (не в Git)
├── tdlib-session-id*/     # Telegram-сессия (не в Git)
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

Telegram API-настройки и токены авторизации TikTok/Google Drive хранятся в `rieltor.db`. При первом запуске отсутствующие
Telegram API-настройки импортируются из переменных окружения или `.env`. `TELEGRAM_USER_ID` в SQLite не сохраняется:
он обязательно читается при каждом запуске из окружения/`.env` и выбирает папку
`tdlib-session-id<TELEGRAM_USER_ID>` рядом с JAR. При обновлении старая строка `TELEGRAM_USER_ID` автоматически удаляется
из `app_secrets`.

`TIKTOK_CLIENT_KEY`, `TIKTOK_CLIENT_SECRET`, `GOOGLE_CLIENT_ID` и `GOOGLE_CLIENT_SECRET` в SQLite не сохраняются и при
каждом запуске обязательно читаются из переменных окружения или локального `.env`.

Запуск:

```powershell
$env:TIKTOK_CLIENT_KEY = "ваш_client_key"
$env:TIKTOK_CLIENT_SECRET = "ваш_client_secret"
$env:TIKTOK_REDIRECT_URI = "https://api.rieltor.dpdns.org/auth/tiktok/callback"
$env:GOOGLE_CLIENT_ID = "ваш_client_id.apps.googleusercontent.com"
$env:GOOGLE_CLIENT_SECRET = "ваш_client_secret"
$env:GOOGLE_REDIRECT_URI = "https://api.rieltor.dpdns.org/auth/google/callback"
$env:TELEGRAM_USER_ID = "ваш_telegram_user_id"
.\gradlew.bat run
```

Сервер стартует на `http://localhost:8383`. Пользовательский Telegram-клиент TDLight открывает сессию из
`tdlib-session-id<TELEGRAM_USER_ID>` рядом с JAR. Фото из настроенных Telegram-чатов передаются через TikTok Photo Direct
Post; caption перед публикацией очищается от внутренних контактов, комиссий, процентов оформления и Google Drive-ссылок,
а JPEG/WebP из указанных в тексте файлов или папок Google Drive добавляются к фотографиям сообщения (до 35 изображений
в одном TikTok-посте). Затем caption приводится к единой структуре с публичным контактом Ірини и хештегами. Остальные
чаты игнорируются. Старый `autoposter` нельзя запускать
одновременно с этим backend: два процесса не должны открывать одну TDLib-сессию.

Перед загрузкой фотографий backend извлекает из исходного caption цену и адрес, нормализует их и вместе с ID форумной
темы использует как ключ объявления. Каждое входящее сообщение сохраняется в `received_telegram_messages`, а успешно
отправленный TikTok-пост — в `published_reposts`. Повтор с новым Telegram message ID, но тем же ключом
`messageThreadId + цена + адрес`, остаётся в истории со статусом `DUPLICATE` и повторно не публикуется. Если цену или
адрес извлечь невозможно, сохраняется только защита от повторной доставки того же Telegram update ID.

### Подключение Google Drive

В Google Cloud Console включите Google Drive API и создайте OAuth client типа **Web application**. В список Authorized
redirect URIs добавьте `https://api.rieltor.dpdns.org/auth/google/callback`. Если приложение находится в режиме Testing,
добавьте рабочий Google-аккаунт в Test users. После запуска backend откройте единую страницу подключений
`https://rieltor.dpdns.org/connect.html`, войдите под аккаунтом с доступом к папкам и подтвердите read-only доступ.
Refresh token сохранится в локальной SQLite-базе и будет обновлять доступ без повторного входа.
OAuth-форма сразу подсказывает публичный аккаунт `irinalinnik.lee@gmail.com`; отдельная переменная
`GOOGLE_ACCOUNT_HINT` не используется.

### Мониторинг форумных чатов Telegram

Родительский форум укажите в `TELEGRAM_MONITORED_CHAT_ID`, например `-1002681732909`. ID его тем храните отдельно в
`TELEGRAM_MONITORED_MESSAGE_THREAD_IDS` через запятую, например `5242880,4194304`. Если список тем пуст, обрабатывается
весь чат. Фото из перечисленных источников передаются в TikTok. Изменения `.env` применяются после перезапуска backend.
Старый составной формат `TELEGRAM_MONITORED_TOPICS` пока поддерживается для обратной совместимости.

Для сборки JAR под Linux-сервер из Windows укажите native-классификатор:

```powershell
.\gradlew.bat shadowJar -PtdlightNativeClassifier=linux_amd64_gnu_ssl3
```

### Запуск JAR на Linux-сервере

Скопируйте собранный `rieltorSite-all.jar` в `/home/exxus/rieltorSite/`, перейдите в эту папку и запустите:

```bash
cd /home/exxus/rieltorSite
java -jar rieltorSite-all.jar
```
```bash
cd /home/exxus/rieltorSite
java -jar rieltorSite-all-linux.jar
```

``
.\gradlew.bat shadowJar -PtdlightNativeClassifier=linux_amd64_gnu_ssl3
Copy-Item `
  -LiteralPath "build\libs\rieltorSite-all.jar" `
  -Destination "build\libs\rieltorSite-all-linux.jar" `
  -Force
``

Команду нужно выполнять от того же пользователя, которому доступны `rieltor.db` и папка сессии
`tdlib-session-id<TELEGRAM_USER_ID>`. Для работы в фоне используйте уже настроенный сервис `rieltor.service`,
а после замены JAR перезапускайте его командой `sudo systemctl restart rieltor.service`.

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

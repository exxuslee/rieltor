# Сайт Ірини Ліннік

Репозиторий объединяет **статический сайт** (GitHub Pages из папки `docs/`) и **Kotlin/Ktor backend** для автоматической
передачи фотографий из Telegram и связанных папок Google Drive в TikTok и Threads.

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

Telegram API-настройки и токены авторизации TikTok/Threads/Google Drive хранятся в `rieltor.db`. При первом запуске отсутствующие
Telegram API-настройки импортируются из переменных окружения или `.env`. `TELEGRAM_USER_ID` в SQLite не сохраняется:
он обязательно читается при каждом запуске из окружения/`.env` и выбирает папку
`tdlib-session-id<TELEGRAM_USER_ID>` рядом с JAR. При обновлении старая строка `TELEGRAM_USER_ID` автоматически удаляется
из `app_secrets`.

`TIKTOK_CLIENT_KEY`, `TIKTOK_CLIENT_SECRET`, `THREADS_APP_ID`, `THREADS_APP_SECRET`, `GOOGLE_CLIENT_ID` и
`GOOGLE_CLIENT_SECRET` в SQLite не сохраняются и при
каждом запуске обязательно читаются из переменных окружения или локального `.env`.

Запуск:

```powershell
$env:TIKTOK_CLIENT_KEY = "ваш_client_key"
$env:TIKTOK_CLIENT_SECRET = "ваш_client_secret"
$env:TIKTOK_REDIRECT_URI = "https://api.rieltor.dpdns.org/auth/tiktok/callback"
$env:THREADS_APP_ID = "ваш_threads_app_id"
$env:THREADS_APP_SECRET = "ваш_threads_app_secret"
$env:THREADS_REDIRECT_URI = "https://api.rieltor.dpdns.org/auth/threads/callback"
$env:GOOGLE_CLIENT_ID = "ваш_client_id.apps.googleusercontent.com"
$env:GOOGLE_CLIENT_SECRET = "ваш_client_secret"
$env:GOOGLE_REDIRECT_URI = "https://api.rieltor.dpdns.org/auth/google/callback"
$env:TELEGRAM_USER_ID = "ваш_telegram_user_id"
.\gradlew.bat run
```

Сервер стартует на `http://localhost:8383`. Пользовательский Telegram-клиент TDLight открывает сессию из
`tdlib-session-id<TELEGRAM_USER_ID>` рядом с JAR. Фото из настроенных Telegram-чатов независимо передаются через TikTok Photo Direct
Post; caption перед публикацией очищается от внутренних контактов, комиссий, процентов оформления и Google Drive-ссылок,
а JPEG/WebP из указанных в тексте файлов или папок Google Drive добавляются к фотографиям сообщения (до 35 изображений
в одном TikTok-посте) и Threads (до 20 фото в карусели). Затем caption приводится к единой структуре с публичным контактом
Ірини и хештегами. Ошибка одного сервиса не отменяет публикацию в другом. Остальные
чаты игнорируются. Старый `autoposter` нельзя запускать
одновременно с этим backend: два процесса не должны открывать одну TDLib-сессию.

### Поток обработки backend

TDLib-адаптер только получает сообщения, собирает альбомы и передаёт подготовленные сообщения через
`TelegramMessageSource`. Новое сообщение передаётся в repost-конвейер через 30 минут; перед передачей backend повторно
получает его из TDLib, поэтому в публикацию попадает актуальный отредактированный текст или подпись.
`TelegramRepostCoordinator` наблюдает поток последовательно, поэтому два сообщения не обрабатываются
параллельно. Назначения одного сообщения также публикуются по очереди (сначала TikTok, затем Threads), чтобы не создавать
пики CPU, памяти и сетевых соединений на VM с 1 OCPU/1 ГБ RAM. Количество загружаемых из Drive и передаваемых в один
сервис фотографий задаётся через `REPOST_MAX_PHOTO_COUNT` и по умолчанию ограничено 10. Google Drive, TikTok и Threads подключены к application use case через отдельные интерфейсы и не импортируются в
Telegram transport. Состояние TDLib-соединения и состояние repost-конвейера публикуются раздельными `StateFlow`, а их
изменения записываются в журнал.

Перед загрузкой фотографий backend извлекает из исходного caption цену и адрес, нормализует их и вместе с ID форумной
темы использует как ключ объявления. Каждое входящее сообщение сохраняется в `received_telegram_messages`, а успешно
результат каждого назначения — в `repost_publications`. Повтор с новым Telegram message ID, но тем же ключом
`destination + messageThreadId + цена + адрес` не публикуется повторно именно в этом сервисе. Если TikTok уже успешен,
а Threads временно упал, повтор затронет только Threads. Если цену или
адрес извлечь невозможно, сохраняется только защита от повторной доставки того же Telegram update ID.

### Подключение Google Drive

В Google Cloud Console включите Google Drive API и создайте OAuth client типа **Web application**. В список Authorized
redirect URIs добавьте `https://api.rieltor.dpdns.org/auth/google/callback`. Если приложение находится в режиме Testing,
добавьте рабочий Google-аккаунт в Test users. После запуска backend откройте единую страницу подключений
`https://rieltor.dpdns.org/connect.html`, войдите под аккаунтом с доступом к папкам и подтвердите read-only доступ.
Refresh token сохранится в локальной SQLite-базе и будет обновлять доступ без повторного входа.
OAuth-форма сразу подсказывает публичный аккаунт `irinalinnik.lee@gmail.com`; отдельная переменная
`GOOGLE_ACCOUNT_HINT` не используется.

### Подключение Threads

В Meta for Developers создайте приложение с Threads API, укажите callback
`https://api.rieltor.dpdns.org/auth/threads/callback` и разрешения `threads_basic`, `threads_content_publish`.
Добавьте `THREADS_APP_ID`, `THREADS_APP_SECRET` и `THREADS_REDIRECT_URI` в окружение или `.env`, перезапустите backend,
затем откройте `https://rieltor.dpdns.org/connect.html` и подключите рабочий Threads-аккаунт. Долгоживущий пользовательский
токен хранится в SQLite и обновляется отдельно от TikTok.

### Мониторинг форумных чатов Telegram

Родительский форум укажите в `TELEGRAM_MONITORED_CHAT_ID`, например `-1002681732909`. ID его тем храните отдельно в
`TELEGRAM_MONITORED_MESSAGE_THREAD_IDS` через запятую, например `5242880,4194304`. Если список тем пуст, обрабатывается
весь чат. Фото из перечисленных источников передаются в подключённые TikTok и Threads. Изменения `.env` применяются после перезапуска backend.
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
который ограничивает JVM одним процессором, heap до 384 МБ, direct memory до 128 МБ и IO-пул двумя потоками.
После замены JAR и unit-файла выполните `sudo systemctl daemon-reload && sudo systemctl restart rieltor.service`.

Конфигурация `api.rieltor.dpdns.org.nginx` отдаёт `/media/` напрямую из
`/home/exxus/rieltorSite/media/`, минуя JVM и общий API rate limit. После её замены проверьте и перезагрузите nginx:

```bash
sudo nginx -t
sudo systemctl reload nginx
```

TikTok и Threads получают фотографии по `https://api.rieltor.dpdns.org/media/...`, поэтому в настройках Content Posting API нужно
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

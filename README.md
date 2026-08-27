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
окружения или `.env`. Исключение — `TELEGRAM_USER_ID`: значение из окружения или `.env` читается при каждом запуске,
имеет приоритет над SQLite и выбирает папку `data/telegram/tdlib-session-id<TELEGRAM_USER_ID>`. Для локального переноса
Telegram credentials из старого проекта выполните:

```powershell
.\gradlew.bat importLocalSecrets -PlegacyAutoposterDir="D:/Android/PRO/autoposter"
```

Для безопасного обновления отдельного секрета, например Telegram API hash:

```powershell
$env:SECRET_VALUE = "новое_значение"
.\gradlew.bat setLocalSecret -PsecretName=TELEGRAM_API_HASH
Remove-Item Env:SECRET_VALUE
```

Запуск:

```powershell
$env:TIKTOK_CLIENT_KEY = "ваш_client_key"
$env:TIKTOK_CLIENT_SECRET = "ваш_client_secret"
$env:TIKTOK_REDIRECT_URI = "https://api.rieltor.dpdns.org/auth/tiktok/callback"
.\gradlew.bat run
```

Сервер стартует на `http://localhost:8383`. Пользовательский Telegram-клиент TDLight открывает сессию из
`data/telegram/tdlib-session-id<TELEGRAM_USER_ID>`. Фото из настроенных Telegram-чатов передаются через TikTok Photo Direct
Post; caption используется как title/description. Остальные чаты игнорируются. Старый `autoposter` нельзя запускать
одновременно с этим backend: два процесса не должны открывать одну TDLib-сессию.

### Мониторинг форумных чатов Telegram

Укажите ID родительских чатов в `TELEGRAM_MONITORED_CHANNEL_IDS`, а ID корневых сообщений нужных форумных тем — в
`TELEGRAM_MONITORED_TOPIC_IDS`; несколько значений разделяются запятыми. Сообщения с фотографиями из перечисленных тем
передаются в TikTok. Если список тем пуст, обрабатываются все темы перечисленных чатов. Изменения `.env` применяются
после перезапуска backend.

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

Команду нужно выполнять от того же пользователя, которому доступны `data/rieltor.db` и папка сессии
`data/telegram/tdlib-session-id<TELEGRAM_USER_ID>`. Для работы в фоне используйте уже настроенный сервис `rieltor.service`,
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

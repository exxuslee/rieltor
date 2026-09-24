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
├── secrets.json           # Секреты и OAuth-токены (не в Git)
├── rieltor.db             # Только накопительные данные Room/SQLite (не в Git)
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

### Каталог в SQLite

`adsTab` хранит готовые объекты продажи: `price` — общая цена объекта в целых USD,
`createdAt` — дата создания записи. Тип сделки, исходная валюта и цена за м²/сотку
обрабатываются до сохранения. Аренда и некорректные цены не попадают в активный каталог.
Исходные сообщения остаются в `incoming_telegram_messages`; пара `chatId` + `messageId` связывает их с объектом,
а `sourceRevision` используется для проверки актуальности перед репостом. История TikTok и Threads
остаётся в `adsTab`, дополнительных таблиц нет.

`adsTab.adId` — уникальный ключ объявления:
`senderId.userId:location:typeOfRealty:price:areaM2:landAreaSotka`.
`incoming_telegram_messages.userId` хранит числовой ID отправителя. Он поступает из TDLib
`senderId.userId`; историческая миграция заполняет его из `rawMessage`.
В версии 24 полный дамп `rawMessage` удалён из `incomeTab`: для определения автора
был добавлен компактный `senderIdentity`. В версии 27 это поле удалено;
автор определяется только по `userId`, а отсутствующие числовые ID восстанавливаются из `senderIdentity`.
В версии 25 удалён неиспользуемый `incomeTab.receivedAt`; дата источника `sourceCreatedAt` сохранена.
Цена берётся после нормализации в целые USD; площади записываются без лишних нулей,
отсутствующие значения — `null`. Если `userId` неизвестен (в том числе для отправителя-канала), используется
`unknown-<chatId>:<messageId>`. Существующие ключи объявлений миграция не изменяет.
Повторные посты объединяются только по `adId`, независимо от чата, темы и ссылок Drive.
Новая цена в новом посте означает новый ключ; редактирование того же Telegram-поста обновляет существующий объект.
`sourceRevision` оставлен для обнаружения изменений исходного сообщения перед репостом:
даже при неизменном `adId` текст и фотографии могут измениться.

База создаётся Room из текущих моделей. Для версий 17 → 18 → 19 → 20 → 21 предусмотрены миграции.
Миграция 19 → 20 вычисляет `adId` по сохранённым данным, оставляет свежую карточку при совпадении ключей
и переносит в неё историю публикаций. Исходные сообщения сохраняются.
Миграция 20 → 21 удаляет из обеих таблиц колонку `sourceKey` и её индексы;
связь с Telegram сохраняется через существующие `chatId` и `messageId`.
Включён `fallbackToDestructiveMigration(true)`: при смене версии без пути миграции
Room удаляет все таблицы базы и создаёт их заново. Каталог, входящие сообщения и история
репостов при этом очищаются. При обычном перезапуске с той же версией данные сохраняются.
Настройки и токены в JSON не сбрасываются. При изменении схемы нужно увеличивать версию
`@Database`; fallback не исправляет несовпадение схемы при неизменной версии.

### Настройка и запуск

Все секреты приложения и OAuth-токены TikTok/Threads/Google Drive хранятся в локальном `secrets.json`. Файл исключён
из Git, а при обновлении токена приложение атомарно перезаписывает его. Пример структуры находится в
`secrets.example.json`; путь задаётся полем `secretsPath` в `settings.json`. Room/SQLite содержит только накапливаемые данные:
очередь, историю репостов и состояние лимитов.

Все несекретные настройки находятся в локальном `settings.json`; образец — `settings.example.json`.

Заполните `secrets.json` перед первым запуском. Значения в секции `tokens` заполняются OAuth-подключениями автоматически.

Запуск после заполнения `secrets.json`:

```powershell
.\gradlew.bat run
```

### Заявки с лендинга

Формы на `https://rieltor.dpdns.org` отправляют JSON на `POST /v1/landing/leads`; backend проверяет тип и поля формы, ограничивает частые запросы и передаёт сообщение в Telegram Bot API. Токен бота и chat ID находятся в секции `secrets` файла `secrets.json`.

### Telegram-бот подготовки объявления

Добавьте `TELEGRAM_LISTING_BOT_TOKEN` в секцию `secrets` файла `secrets.json` и перезапустите backend. Если отдельный
токен не задан, используется `LANDING_TELEGRAM_BOT_TOKEN`. Бот сразу принимает текст объявления с Google Drive-ссылкой,
применяет тот же форматтер, что TikTok и Threads, скачивает фотографии и отвечает в исходный чат. Фотографии отправляются
пакетами до 10; при количестве 11, 21 и т. п. последний одиночный файл отправляется отдельным сообщением. За один запрос
скачивается до 100 фото; лимит можно изменить через `TELEGRAM_LISTING_BOT_MAX_PHOTO_COUNT` в диапазоне 1–1000.
Задержка ожидания возможного редактирования применяется только к автоматически отслеживаемым TDLib-чатам.

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
`TelegramMessageSource`. Новое сообщение передаётся в repost-конвейер через 20 минут; перед передачей backend повторно
получает его из TDLib, поэтому в публикацию попадает актуальный отредактированный текст или подпись.
Сообщение без распознанной цены или Google Drive-ссылки получает отдельный отказной статус и не ставится на публикацию.
Остальные сообщения сохраняются в постоянной FIFO-очереди SQLite на 64 ожидающих позиции, поэтому очередь переживает
перезапуск backend. `TelegramRepostCoordinator` обрабатывает только её голову, поэтому два сообщения не обрабатываются
параллельно. Назначения одного сообщения также публикуются по очереди (сначала TikTok, затем Threads); ошибка TikTok
останавливает пакет до повторной попытки и не позволяет Threads уйти вперёд. Это также не создаёт
пики CPU, памяти и сетевых соединений на VM с 1 OCPU/1 ГБ RAM. Количество загружаемых из Drive и передаваемых в один
сервис фотографий задаётся через `REPOST_MAX_PHOTO_COUNT` и по умолчанию ограничено 10. Google Drive, TikTok и Threads подключены к application use case через отдельные интерфейсы и не импортируются в
Telegram transport. Состояние TDLib-соединения и состояние repost-конвейера публикуются раздельными `StateFlow`, а их
изменения записываются в журнал.

Перед загрузкой фотографий backend извлекает из исходного caption цену и адрес, нормализует их и вместе с ID форумной
темы использует как ключ объявления. Каждое входящее сообщение сохраняется в `received_telegram_messages`, а успешно
результат каждого назначения — в `repost_publications`. Повтор с новым Telegram message ID, но тем же ключом
`destination + messageThreadId + цена + адрес` не публикуется повторно именно в этом сервисе. Если TikTok уже успешен,
а Threads временно упал, повтор затронет только Threads. Если адрес извлечь невозможно, сохраняется защита по Telegram
update ID; отсутствие цены блокирует постановку в очередь. Завершённая история Telegram старше 30 дней удаляется
фоновой задачей каждый час, незавершённая очередь не очищается.

### Подключение Google Drive

В Google Cloud Console включите Google Drive API и создайте OAuth client типа **Web application**. В список Authorized
redirect URIs добавьте `https://api.rieltor.dpdns.org/auth/google/callback`. Если приложение находится в режиме Testing,
добавьте рабочий Google-аккаунт в Test users. После запуска backend откройте единую страницу подключений
`https://rieltor.dpdns.org/connect.html`, войдите под аккаунтом с доступом к папкам и подтвердите read-only доступ.
Refresh token сохранится в `secrets.json` и будет обновлять доступ без повторного входа.
OAuth-форма сразу подсказывает публичный аккаунт `irinalinnik.lee@gmail.com`; отдельная переменная
`GOOGLE_ACCOUNT_HINT` не используется.

### Подключение Threads

В Meta for Developers создайте приложение с Threads API, укажите callback
`https://api.rieltor.dpdns.org/auth/threads/callback` и разрешения `threads_basic`, `threads_content_publish`.
Добавьте `THREADS_APP_ID`, `THREADS_APP_SECRET` и `THREADS_REDIRECT_URI` в `secrets.json`, перезапустите backend,
затем откройте `https://rieltor.dpdns.org/connect.html` и подключите рабочий Threads-аккаунт. Долгоживущий пользовательский
токен хранится в том же JSON и обновляется отдельно от TikTok. Автоматическая публикация в Threads временно отключена по
умолчанию; для её возврата установите `threadsEnabled: true` в `settings.json`.

Режим TikTok задаётся полем `tikTokMode` в `settings.json`: `POST` (значение по умолчанию) сразу публикует в TikTok, а `DRAFT` загружает
материал в черновики TikTok. После загрузки TikTok присылает уведомление во входящие приложения — откройте его,
отредактируйте публикацию и опубликуйте вручную. Настройка не влияет на Threads: при включённом `threadsEnabled` он
публикует сразу, если аккаунт подключён.
Для `DRAFT` аккаунт должен заново выдать приложению scope `video.upload`, если он отсутствует в текущем токене.

Общий мастер-лимитер находится в `TelegramRepostCoordinator` и применяется к пакету TikTok + Threads до передачи
сообщения репостерам. Он пропускает не более 36 сообщений за скользящие 24 часа и выдерживает минимум 20 минут между
пакетами. Настройки `maxMessagesPer24Hours` и `minIntervalMs` находятся в `settings.json`;
состояние лимитера сохраняется в SQLite и переживает перезапуск backend.

### Мониторинг форумных чатов Telegram

Источники задаются массивом `monitoredTelegramChats` в `settings.json`: у каждого чата свой `chatId` и свой массив
`messageThreadIds`. Поэтому можно безопасно указать два и более форумных чатов с разными темами. Пустой массив тем
означает мониторинг всего чата. Фото из перечисленных источников передаются в подключённые TikTok и Threads; изменения
применяются после перезапуска backend.

```json
"monitoredTelegramChats": [
  { "chatId": -1002681732909, "messageThreadIds": [5242880, 4194304] },
  { "chatId": -1001234567890, "messageThreadIds": [50180] }
]
```

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

Команду нужно выполнять от того же пользователя, которому доступны `secrets.json`, `rieltor.db` и папка сессии
`tdlib-session-id<TELEGRAM_USER_ID>`. Для работы в фоне используйте уже настроенный сервис `rieltor.service`,
который ограничивает JVM одним процессором, heap до 384 МБ, direct memory до 128 МБ и IO-пул двумя потоками.
После замены JAR и unit-файла выполните `sudo systemctl daemon-reload && sudo systemctl restart rieltor.service`.

Конфигурация `api.rieltor.dpdns.org.nginx` отдаёт `/media/` напрямую из
`/var/www/rieltor/media/`, минуя JVM и общий API rate limit. Путь задаётся один раз через
`mediaDirectory` в `settings.json`. При первом переходе со старого пути выполните:

```bash
sudo install -d -o exxus -g www-data -m 0755 /var/www/rieltor/media
sudo cp -a /home/exxus/rieltorSite/media/. /var/www/rieltor/media/
sudo find /var/www/rieltor/media -type f -exec chmod 0644 {} +
```

После замены конфигурации проверьте и перезагрузите nginx:

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

### Однократная загрузка истории Telegram

Остановите основное приложение и из корня проекта выполните:

```powershell
.\gradlew.bat importTelegramHistory

java -cp rieltorSite-all.jar com.rieltor.tools.TelegramHistoryMainKt
```

Отдельный main: `com.rieltor.tools.TelegramHistoryMainKt`. Он загружает сообщения
за последний календарный месяц (UTC, до момента запуска) только из настроенных
чатов/подгрупп: объединение `monitoredTelegramChats` и ключей `topicTypeMapping`
из `settings.json`, как в основном приложении.
История чатов читается постранично, подгруппы фильтруются перед сохранением.

Используются `databasePath`, `secretsPath`, `APP_PROJECT_ROOT` и сессия
`tdlib-session-id<TELEGRAM_USER_ID>`. Нужны только Telegram-секреты
`TELEGRAM_API_ID`, `TELEGRAM_API_HASH`, `TELEGRAM_USER_ID` из файла секретов.
При необходимости подтвердите авторизацию Telegram;
ожидание ограничено пятью минутами. Одновременно использовать эту сессию
основным приложением нельзя; блокировка `settings.json` предотвращает такой запуск.

Сообщения сохраняются через стандартный `CatalogRepository.receive` в
`incoming_telegram_messages` с обычным периодом стабилизации. Повторный запуск
не создаёт дубли и не сбрасывает обработку неизменённых сообщений. При ошибке
запуск завершается с ошибкой, уже сохранённые сообщения остаются в базе —
после устранения причины можно повторить команду.

Импорт завершается самостоятельно и не запускает сервер, обработчики медиа
или публикацию. После завершения запустите основную программу: она подхватит
очередь стандартной логикой. Счётчик в логе включает уже существующие сообщения.

В версии 26 таблица `listings` переименована в `adsTab` с сохранением объявлений и индексов.

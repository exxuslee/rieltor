# План сайта Ірини Ліннік

## Цель

Локальный адаптивный сайт персонального риелтора, который приводит обращения покупателей и собственников недвижимости.
Интерфейс и контент — на украинском языке.

## Реализованные разделы

- Главная: позиционирование, преимущества, избранные объекты, видеообзоры и призывы к действию.
- Покупка: форма персонального подбора.
- Объекты: статические индексируемые страницы с характеристиками и записью на просмотр.
- Продажа: преимущества, процесс и заявка на оценку.
- О риелторе, FAQ, контакты и политика конфиденциальности.
- Светлая и тёмная темы с локальным сохранением выбора.

## Источник данных

Данные объектов хранятся в `js/data.js`. После изменения данных необходимо выполнить:

```powershell
node scripts/generate-seo-pages.mjs
```

## Предрелизная проверка

```powershell
node scripts/check-smoke.mjs
node scripts/check-seo.mjs
```

Также вручную проверить desktop и mobile: навигацию с клавиатуры, тему, фильтры, страницы объектов и локальные состояния
форм.

## Перед продакшеном

- Перенести отправку форм на защищённый серверный endpoint, отозвать публичный Telegram-токен и очистить его из истории
  Git.
- Подтвердить актуальность, цены и права на публикацию всех объектов и медиа.
- Проверить формы с реальным обработчиком, rate limit и антиспамом.
- Выполнить внешние проверки из `SEO-CHECKLIST.md` после публикации.

## Telegram → TikTok

- Backend организован по слоям `domain`, `application`, `infrastructure`, `web`; сборка зависимостей вынесена в
  Koin-модули `configuration`, `persistence`, `network`, `application` и `integration`.
- `TelegramClientAdapter` отвечает только за TDLib transport и отдаёт сообщения через `TelegramMessageSource.messages`.
  `TelegramRepostCoordinator` последовательно наблюдает этот `Flow` и вызывает application use case публикации. Состояния
  соединения и repost-конвейера доступны отдельно через `StateFlow<TelegramSourceState>` и
  `StateFlow<RepostFlowState>`.
- TDLib-маппинг/скачивание и startup-диагностика разделены на `TelegramMessageMapper` и `TelegramDiagnostics`.
  SQLite-доступ также разделён по системам: настройки, TikTok tokens, Google Drive tokens и Telegram repost history имеют
  отдельные реализации репозиториев.
- Telegram API-настройки, TikTok access/refresh tokens и журнал обработанных Telegram updates хранятся в локальной
  SQLite-базе `rieltor.db` рядом с JAR (файл исключён из Git). `TELEGRAM_USER_ID`, `TIKTOK_CLIENT_KEY` и
  `TIKTOK_CLIENT_SECRET` всегда читаются из окружения или `.env` и в SQLite не сохраняются.
- Пользовательский Telegram-клиент TDLight использует локальную сессию, принимает фото пользователя `530667295` только
  из личного чата «Избранное», сохраняет изображение под случайным именем и инициирует TikTok Photo Direct Post.
  Повторная обработка одного сообщения блокируется базой.
- Если текст или подпись сообщения содержит ссылку на файл/папку Google Drive, backend получает доступ через OAuth 2.0
  со scope `drive.readonly`, скачивает JPEG/WebP из Drive и добавляет их в тот же TikTok-фотопост (не более 35 фото).
  Access/refresh tokens Google хранятся в SQLite; client ID/secret всегда читаются из окружения или `.env`.
- Единая страница подключения Google Drive и TikTok: `docs/connect.html`. Старые адреса `google-connect.html` и
  `tiktok-connect.html` перенаправляют на неё. Google callback backend:
  `https://api.rieltor.dpdns.org/auth/google/callback`.
- Перед публикацией отдельный `TikTokMessageFilter` удаляет исходные контакты, Google Drive-ссылки, комиссию, проценты
  оформления и упоминания АН «Новатор», затем формирует заголовок, основные параметры, описание, контакт Ірини и хештеги.
- Отдельный application-сервис `TelegramRepostTracker` регистрирует все входящие сообщения и не допускает повторную публикацию
  объявления с тем же нормализованным ключом `messageThreadId + цена + адрес`. В SQLite раздельно хранятся история
  `received_telegram_messages` и подтверждённые публикации `published_reposts`; неудачная публикация освобождает ключ для
  повторной попытки.
- Старый `tiktok-tokens.json` автоматически переносится в SQLite при первом запуске.
- Папка TDLib-сессии `tdlib-session-id<TELEGRAM_USER_ID>` находится рядом с JAR и не публикуется в Git.
- Фоновая очистка запускается при старте backend и затем каждый час, удаляя из `media` изображения старше 24 часов.
- Для Photo API необходимо добавить Content Posting API, подтвердить домен `https://api.rieltor.dpdns.org` в TikTok
  Developer Portal и развернуть runtime-файлы рядом с JAR.

# Сайт Ірини Ліннік

## Локальный запуск

В PowerShell откройте папку проекта и выполните:

```powershell
python -m http.server 4173
```

После этого сайт будет доступен по адресу [http://localhost:4173](http://localhost:4173).

Для остановки сервера нажмите `Ctrl + C` в этом же окне PowerShell.

## Отправка форм в Telegram

Все три формы отправляют данные напрямую в Telegram Bot API из `js/forms.js`. Дополнительный серверный обработчик не требуется.

## Обновление каталога и SEO-страниц

Данные объектов находятся в `js/data.js`. После их изменения пересоздайте статические страницы объектов и sitemap:

```powershell
node scripts/generate-seo-pages.mjs
```

После замены исходных PNG также обновите оптимизированные WebP:

```powershell
python scripts/optimize-images.py
```

Внешние шаги после публикации перечислены в `SEO-CHECKLIST.md`.

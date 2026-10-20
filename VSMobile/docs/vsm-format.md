# Формат плагинов .vsm

`.vsm` — JSON-файл до 2 МБ. Плагины пишутся на JavaScript (QuickJS), у каждого плагина свой постоянный движок.

## Манифест (версия 2)

```json
{
  "format": "vsm",
  "version": 2,
  "id": "my-plugin",
  "name": "Мой плагин",
  "pluginVersion": "1.0.0",
  "description": "Описание",
  "author": "Автор",
  "permissions": ["editor", "ui"],
  "commands": [{ "id": "run", "title": "Запустить", "pin": true }],
  "panels": [{ "id": "main", "title": "Панель", "html": "<h3>Привет</h3>" }],
  "languages": [{ "extensions": ["foo"], "keywords": ["let"], "rules": [{ "regex": "//.*", "color": "#6A9955", "italic": true }] }],
  "modules": { "utils.js": "module.exports = { twice: function (x) { return x * 2; } };" },
  "code": "vsm.command('run', function () { vsm.ui.toast('ok'); });"
}
```

- `id` команд, панелей и плагина: `[a-z0-9][a-z0-9_-]{1,63}`.
- Нужна хотя бы одна команда, панель или непустой `code`.
- `pin: true` — команда выводится кнопкой над панелью символов.
- Цвет правила подсветки — `#RRGGBB`, `regex` — регулярное выражение Kotlin.
- Версия 1: одна или несколько команд и функция `run(cmd, ctx)` в `code`; права — только `editor`.

## Разрешения

Пользователь выдаёт их при установке и может менять позже. Переустановка не даёт новых прав без согласия.

| Ключ | Что даёт |
|---|---|
| editor | текст, выделение и вкладки редактора |
| files | чтение и запись файлов проекта |
| network | HTTP-запросы по https |
| storage | собственное хранилище до 4 МБ |
| ui | диалоги, уведомления, панели |
| ai | вопросы к ИИ-ассистенту |
| python | запуск Python-кода |
| clipboard | чтение и запись буфера обмена |

Сочетания files+network и editor/clipboard+network помечаются предупреждением.

## API

Все вызовы синхронные, ошибка бросает `Error` с полем `code`.

| Область | Методы |
|---|---|
| `vsm.log(...)`, `vsm.app.info()` | всегда доступны |
| `vsm.editor` | getText, setText, getSelection, setSelection, replaceSelection, insertAt, fileName, tabs, openFile |
| `vsm.fs` | read, write, list, exists, mkdir, remove, rename, move, readJson, writeJson |
| `vsm.storage` | get, set, remove, getJson, setJson |
| `vsm.ui` | toast, confirm, prompt, pick, openPanel, closePanel, postToPanel |
| `vsm.http` | request, get, post, getJson, postJson (ответ до 10 МБ) |
| `vsm.ai` | ask |
| `vsm.python` | run |
| `vsm.clipboard` | read, write |
| `vsm.panel` | post, onMessage (внутри панели) |
| `vsm.command(id, fn)`, `vsm.commands.run(id, ctx)` | регистрация и вызов команд |
| `vsm.on / once / off` | события |
| `setTimeout`, `setInterval`, `clearTimeout`, `clearInterval`, `console`, `require` | в движке |

Пути в `fs` относительные от корня проекта. `..`, `.`, пустые сегменты, ведущий `/` и `\` запрещены; корень проекта удалить нельзя.

## События

`app.started`, `project.opened`, `project.closed`, `file.opened`, `file.saved`, `file.closed`, `text.changed` (с задержкой 300 мс), `panel.message:<id>`.

## Панели

HTML показывается в WebView внутри нижней шторки. CSP: без network `connect-src 'none'`, с network — `https:`. Файлы и content-URI недоступны, навигация заблокирована. Внутри доступен тот же `vsm`. Тема — CSS-переменные `--vsm-*` (фон, текст, акцент, граница) и базовые стили с нулевой специфичностью, которые можно переопределить.

## Лимиты

- Вызов команды или события — до 60 с; время в `ui.confirm/prompt/pick` не считается.
- Зависший движок отключается, плагин помечается выключенным до ручного включения.
- Коды ошибок: `permission_denied`, `unknown_method`, `bad_args`, `path_denied`, `no_active_file`, `storage_full`, `host_error`, `not_found`.

## Проверка на устройстве

1. Установить `samples/hello.vsm`: выдать права, команда «Привет» появилась в полосе, вставляет текст.
2. `samples/language-foo.vsm`: файл `a.foo` подсвечивается.
3. `samples/hang.vsm`: команда «Зависнуть» через 60 с отключает плагин, приложение живо.
4. Снять разрешение `editor` у hello: команда выдаёт `permission_denied`.
5. Удалить плагин: пропали команды и подсветка.

# AG Widgets — руководство по установке (Windows)

Установка десктопного виджета занимает около 5 минут. Android-приложение и виджет 4×2 ставятся отдельно и необязательны; Android выходит в двух сборках — **AG Widgets** (Antigravity + ZCode) и **AG Context** (только Antigravity).

Английская версия: [INSTALL_GUIDE.md](INSTALL_GUIDE.md) · Автоматическая установка через ИИ-агента: [AGENT_INSTALL.md](AGENT_INSTALL.md) · Проект: [../README.ru.md](../README.ru.md).

**Что вы получите:**

1. Десктоп-виджет 400×640: контекст открытого чата Antigravity, лимиты 5ч/неделя, ваучеры и баланс ZCode.
2. Хук и sidecar в Antigravity, которые обновляют данные без ручных действий.
3. Опционально APK и виджет 4×2 на Android — **AG Widgets** или **AG Context** — который работает при выключенном ПК.

## 1. Требования (Windows)

| Требование | Версия | Как проверить |
|---|---|---|
| Windows | 10 или 11 (acrylic-размытие — только Windows 11 22H2+) | `winver` |
| Python | 3.10+ с python.org, не из Microsoft Store | `where.exe python`, `python --version` |
| Node.js и npm | Node.js 22.12+ (Electron 45) | `node -v`, `npm -v` |
| Google Antigravity | 2.0, [antigravity.google](https://antigravity.google) | `Test-Path "$HOME\.gemini\bin\agy.exe"` (или `agy --version`, если он в PATH) |
| Android (опционально) | JDK 17, Android SDK (`ANDROID_HOME`), `adb` в PATH | `java -version`, `adb version` |

**Ловушка с Python из Microsoft Store.** Windows подставляет в `PATH` псевдоним `%LOCALAPPDATA%\Microsoft\WindowsApps\python.exe`. Вместо интерпретатора он открывает магазин, а PowerShell завершает вызов кодом 9009. Установщик такую заглушку отвергает.

Проверьте, что первым в `PATH` идёт настоящий Python:

```powershell
where.exe python
python --version
```

Первая строка вывода `where.exe python` не должна указывать в `WindowsApps`. Если указывает:

1. Установите Python 3.10+ с [python.org](https://www.python.org/downloads/) и включите галку **Add python.exe to PATH**.
2. Откройте Параметры -> Приложения -> Дополнительные параметры приложений -> Псевдонимы выполнения приложений и выключите `python.exe` и `python3.exe`.
3. Закройте и снова откройте терминал.

Если `python --version` по-прежнему открывает Store, псевдоним не отключён — повторите шаг 2.

Если Python установлен в нестандартное место, передайте путь установщику: `-PythonPath "C:\Path\To\python.exe"`.

**Antigravity CLI.** `agy.exe` не обязан быть в `PATH`. Код ищет исполняемый файл в таком порядке:

1. переменная окружения `AGY_PATH`;
2. `PATH`;
3. `%USERPROFILE%\.gemini\bin\agy.exe`;
4. `%LOCALAPPDATA%\agy\bin\agy.exe`.

Если Antigravity установлен в другое место, задайте путь явно:

```powershell
setx AGY_PATH "C:\Path\To\agy.exe"
```

После `setx` откройте новый терминал: текущий сеанс переменную не увидит.

Вручную ставить Python- и JS-пакеты не нужно: зависимости Electron подтянет `npm ci`, который запускает установщик.

## 2. Установка десктопа (Windows)

### 2.1 Клонирование

```powershell
git clone https://github.com/DmytroHnylytskyi/ag_widgets.git ag-widgets
cd ag-widgets
```

Если локальная копия уже есть, перейдите в неё — этого достаточно.

### 2.2 Предпросмотр установщика (ничего не пишет)

```powershell
powershell -ExecutionPolicy Bypass -File .\install.ps1 -DryRun
```

Каждое действие печатается с префиксом `[dry-run]`. Файлы, бэкапы, ярлыки и зависимости не создаются, `npm ci` и коллектор не запускаются.

### 2.3 Запуск установщика

```powershell
powershell -ExecutionPolicy Bypass -File .\install.ps1
```

Что он делает:

1. Копирует `~/.gemini/config/hooks.json`, `~/.gemini/config/config.json` и `~/.gemini/antigravity-cli/settings.json` в `%LOCALAPPDATA%\ag-widgets\backups\install-<timestamp>\`. Если какого-то файла ещё нет, он пропускается.
2. Создаёт `%LOCALAPPDATA%\ag-widgets\config.json` из `config.example.json`, если файла ещё нет, и сохраняет в нём `python_path`; существующий конфиг не перезаписывается, в него добавляется только этот ключ.
3. Регистрирует хук `ag-widgets-context` в `~/.gemini/config/hooks.json` на события `PostInvocation` и `Stop`.
4. Записывает манифест sidecar `ag-widgets-collector` (cron `* * * * *`) и включает его в конфиге Antigravity; чужие sidecar-записи сохраняются.
5. Настраивает statusLine Antigravity CLI на `collector\agy_statusline.py`. Чужой statusLine заменяется с предупреждением, прежнее значение остаётся в бэкапе.
6. Выполняет `npm ci` в `desktop\`. Если после этого `desktop\node_modules\electron\dist\electron.exe` отсутствует, докачивает бинарник Electron 45 командой `node node_modules\electron\install.js` — в Electron 45 бинарник больше не приходит через postinstall.
7. Один раз запускает `python collector\collector.py --force --show-topic`: создаёт приватный `ntfy_topic` (96 случайных бит) и печатает его на экран один раз. Если планируете телефон, скопируйте топик сразу; позже его можно напечатать снова через `--show-topic`.
8. Создаёт ярлыки **AG Widgets** на Рабочем столе и в меню «Пуск». Запуск идёт через `start-widget.vbs`, без мелькающей консоли.

Полезные флаги:

```powershell
.\install.ps1 -PythonPath "C:\Path\To\python.exe"   # явный интерпретатор
.\install.ps1 -NoShortcut                           # без ярлыков
.\install.ps1 -DryRun                               # показать шаги, ничего не записывая
.\install.ps1 -Uninstall                            # удалить только записи ag-widgets
```

### 2.4 Проверка установки

```powershell
python collector\collector.py --doctor
python collector\collector.py --doctor-json
python collector\collector.py --doctor --online
```

`--doctor` работает только на чтение и ничего не создаёт. Код выхода `0`, если нет ни одной проверки со статусом `fail`, и `1`, если есть хотя бы одна. Каждая строка начинается с `[OK]`, `[WARN]` или `[FAIL]`; в конце печатается сводка и, если что-то не в порядке, блок «Remediation hints». `--doctor-json` печатает обезличенный машиночитаемый отчёт: топик и токен в него не попадают, домашний каталог схлопывается в `~`, runtime-каталог — в `%LOCALAPPDATA%`. Флаг `--online` добавляет сетевые проверки `ntfy_reachability` и `zcode_api`.

Ожидание: 8 офлайн-проверок со статусом `ok`. Несколько `warn` до первого открытого чата Antigravity и до входа в ZCode — норма. Типичные предупреждения на этом этапе: `annotations_dir`, `state_freshness`, `zcode_api`. Что делать с каждой проверкой, описано в [AGENT_INSTALL.md](AGENT_INSTALL.md).

### 2.5 Вход в ZCode (опционально)

Самый простой путь — кнопка **ВОЙТИ В Z.AI** в карточке `03 / ACCESS`: откроется официальная OAuth-страница z.ai, токен сохранится локально.

То же из командной строки:

```powershell
python collector\zcode_auth.py                     # вход через браузер
python collector\zcode_auth.py --status            # проверить сохранённый токен
python collector\zcode_auth.py --set-token-stdin   # JWT из stdin
python collector\zcode_auth.py --clear-token       # удалить сохранённый токен
```

`--set-token-stdin` читает JWT из стандартного ввода. Никогда не передавайте JWT аргументом CLI: он попадёт в список процессов и историю команд. Без токена карточка честно показывает «не подключено»; scraping-fallback отсутствует.

**AG Context** (сборка для Android только с Antigravity) вообще не содержит ZCode-клиента: ни кнопки входа, ни карточки ZCode, ни запросов к ZCode. Если ваучеры и баланс нужны на телефоне, ставьте **AG Widgets**.

### 2.6 Запуск и управление

Запуск: `start-widget.vbs`, ярлык **AG Widgets** или

```powershell
cd desktop
npm start
```

Кнопки в заголовке: **⟳ / F5** — обновить, **☀/☾** — тема, **⋯** — меню, **−** — свернуть в панель задач, **×** — выход. Правый клик открывает то же меню; перетаскивание заголовка двигает виджет. Размер окна — 400×640, по умолчанию оно поверх остальных окон и живёт в трее.

## 3. Android: приложение и виджет

Один код даёт два подписанных APK с разными application id, поэтому они могут стоять на телефоне одновременно:

| | **AG Widgets** | **AG Context** |
|---|---|---|
| Контекст и лимиты Antigravity | да | да |
| Ваучеры, баланс и OAuth ZCode | да | **нет** |
| Работает без ПК | да, для ZCode | нет |
| package | `dev.agwidgets` | `dev.agwidgets.context` |

У **AG Widgets** `BuildConfig.ZCODE_ENABLED = true` (Antigravity + ZCode) — это рекомендуемая сборка: ZCode работает сразу, а Antigravity можно подключить позже. У **AG Context** `BuildConfig.ZCODE_ENABLED = false`: там нет ни кнопки входа в ZCode, ни карточки ZCode, ни запросов к ZCode.

### 3.1 Скачать готовый APK (или собрать самому)

Релиз `v1.0.0` содержит оба подписанных community-APK, поэтому инструменты сборки не нужны:

- **AG Widgets 1.0.0** (Antigravity + ZCode): [`AG-Widgets-1.0.0.apk`](https://github.com/DmytroHnylytskyi/ag_widgets/releases/latest/download/AG-Widgets-1.0.0.apk)
- **AG Context 1.0.0** (только Antigravity): [`AG-Context-1.0.0.apk`](https://github.com/DmytroHnylytskyi/ag_widgets/releases/latest/download/AG-Context-1.0.0.apk)
- **Windows portable**: `ag-widgets-<ver>-windows-x64.zip` со [страницы релизов](https://github.com/DmytroHnylytskyi/ag_widgets/releases/latest) — распакуйте в любое место и запустите `.\install.ps1`. Node.js **не требуется**: Electron уже внутри архива.

Portable ZIP — это тот же десктопный виджет, коллектор и хук Antigravity, что и при установке из клона, только без шага `npm ci`; ярлык **AG Widgets** создаёт установщик. Оба APK — community-сборки без личного ntfy-топика.

К каждому артефакту в релизе приложен свой `<file>.sha256`, а также общий `SHA256SUMS.txt`. Сверьте сумму перед установкой:

```powershell
Get-FileHash -Algorithm SHA256 .\AG-Widgets-<ver>.apk
Get-Content .\AG-Widgets-<ver>.apk.sha256
```

```bash
sha256sum -c AG-Widgets-<ver>.apk.sha256
```

### 3.2 Сборка community APK

JDK 17 и Android SDK нужны, только если вы собираете локально. Из корня репозитория:

```powershell
cd android
.\gradlew.bat :app:assembleFullDebug -Pcommunity=true
.\gradlew.bat :app:assembleContextDebug -Pcommunity=true
```

Выходы:

```text
android\app\build\outputs\apk\full\debug\app-full-debug.apk
android\app\build\outputs\apk\context\debug\app-context-debug.apk
```

Подписанные release-сборки используют ключ проекта, который хранится вне репозитория:

```powershell
.\gradlew.bat :app:assembleFullRelease :app:assembleContextRelease `
  -Pcommunity=true --no-configuration-cache
```

Полный процесс релиза — в [RELEASING.md](RELEASING.md).

**Для любой передачи APK собирайте только с `-Pcommunity=true`.** Без этого флага сборка берёт `agw.topic` из `android/local.properties` и вшивает ваш личный ntfy-топик в `BuildConfig`. Такой APK раскрывает приватный канал всякому, кто его получит. Личная сборка нужна только для собственного телефона: положите `agw.topic=<your-topic>` в `android/local.properties` (файл в `.gitignore`) и запустите ту же команду без флага — Gradle предупредит, что топик вшит. `-Pcommunity=true` обязателен для любой публичной сборки, включая release.

### 3.3 Установка через ADB (или копированием APK)

```powershell
adb devices
adb install -r android\app\build\outputs\apk\full\debug\app-full-debug.apk
adb install -r android\app\build\outputs\apk\context\debug\app-context-debug.apk
```

Ставьте только нужный APK — один или оба (у них разные application id).

`adb devices` должен показать строку `<serial>   device`. Путь в `adb install` указан от корня репозитория; если вы остались в каталоге `android`, используйте `app\build\outputs\apk\full\debug\app-full-debug.apk`. Для беспроводной отладки включите Параметры разработчика -> Беспроводная отладка, затем выполните `adb pair <ip>:<port>` и `adb connect <ip>:<port>`.

Альтернатива без ADB: скопируйте APK на телефон, откройте его и разрешите установку из неизвестных источников.

Application id — `dev.agwidgets` (**AG Widgets**) и `dev.agwidgets.context` (**AG Context**). Обе сборки могут стоять одновременно. Если раньше было установлено приложение с другим id, удалите его: Android не переносит настройки между разными application id.

### 3.4 Чек-лист первого запуска

1. Откройте приложение и разрешите уведомления (`POST_NOTIFICATIONS`).
2. Выберите режим на экране онбординга:
   - **Только ZCode** (**AG Widgets**): ПК не нужен, ваучеры и баланс телефон проверяет сам. Войти можно позже кнопкой **Войти в Z.ai**.
   - **С компьютером**: понадобится `ntfy_topic`, который печатает установщик. Получить его на ПК: `python collector\collector.py --show-topic`.
   В **AG Context** ZCode-клиента нет, поэтому там доступен только режим «С компьютером» (подпись «AG Context работает вместе с коллектором на компьютере»).
3. Для режима «С компьютером» вставьте топик в поле и нажмите **Сохранить**.
4. Нажмите **Обновить**, чтобы убедиться, что данные с ПК приходят.
5. Опционально (только **AG Widgets**): войдите в Z.ai — тогда телефон сам проверяет ваучеры и баланс.
6. Добавьте виджет 4×2 на экран.

Режим можно сменить позже в разделе «Диагностика». Приложение обновляется каждые 15 минут — это минимальный интервал WorkManager, и он не гарантирован. Виджет 4×2 можно растягивать, тему он берёт у приложения (по умолчанию — системную).

### 3.5 Жесты виджета

- Тап по телу виджета открывает приложение.
- **⟳** на виджете запускает прямое параллельное обновление из ntfy и ZCode с таймаутом 4 с, не дожидаясь фонового WorkManager.
- Растягивание виджета по высоте показывает расширенную полосу контекста.
- Очистка `ntfy_topic` с сохранением сразу сбрасывает кэш состояния ПК и отменяет запланированные напоминания о сбросе квоты. Токен ZCode при этом сохраняется.
- После смены чата на ПК десктоп публикует новый активный чат с троттлингом 15 с (сквозная задержка около 15–20 с), поэтому на телефоне нажмите **⟳**.

## Оптимизация батареи (OEM)

Производители агрессивно убивают фоновую работу. Без этих настроек обновление раз в 15 минут и локальные напоминания о сбросе молча перестают работать.

### Xiaomi / Redmi / POCO (HyperOS / MIUI)

Параметры -> Приложения -> Все приложения -> AG Widgets -> **Автозапуск** включить. Затем Экономия заряда -> **Без ограничений**. Если HyperOS всё равно убивает воркер, откройте «Недавние», зажмите карточку и заблокируйте её.

### Samsung (One UI)

Параметры -> Обслуживание устройства -> Батарея -> Ограничения фоновой активности: AG Widgets НЕ должен быть в «Спящих приложениях» / «Приложениях в глубоком сне» (**Никогда не переводить в спящий режим**). Затем Параметры -> Приложения -> AG Widgets -> Батарея -> **Без ограничений**.

### Google Pixel / Stock Android

Параметры -> Приложения -> AG Widgets -> Использование батареи приложением -> **Разрешить фоновую работу** -> **Без ограничений**.

После настройки нажмите **⟳**, подождите один цикл WorkManager и проверьте на ПК: `python collector\collector.py --doctor --online`.

## 4. Устранение неполадок

| Симптом | Диагностическая команда | Исправление |
|---|---|---|
| Установщик падает с кодом 9009, «python не распознан» или открывается Store | `where.exe python` | Первая строка указывает в `WindowsApps` — это заглушка. Поставьте Python 3.10+ с python.org, отключите псевдонимы выполнения приложений и повторите запуск с `-PythonPath`. |
| `--doctor` показывает `agy_cli: fail` | `python collector\collector.py --doctor-json` | Antigravity не установлен или не найден. Установите Antigravity 2.0, проверьте `%USERPROFILE%\.gemini\bin\agy.exe` или задайте `AGY_PATH`. |
| Окно виджета не появляется, нет бинарника Electron | `Test-Path desktop\node_modules\electron\dist\electron.exe` | Выполните `cd desktop; node node_modules\electron\install.js`. Если файла `install.js` нет, повторите `npm ci`. |
| `zcode_api: fail`: истёк токен, 429 или `fetch failed` | `python collector\zcode_auth.py --status`, лог `%LOCALAPPDATA%\ag-widgets\state\desktop_errors.log` | Войдите заново через **ВОЙТИ В Z.AI** или `--set-token-stdin`. При 429 подождите и повторите. |
| `secrets_storage: fail`: DPAPI не расшифровывает после переноса профиля Windows | `python collector\collector.py --doctor-json` | Шифртекст принадлежит старому профилю. Заново введите токен через `--set-token-stdin`; топик создаст коллектор. Не копируйте `secrets.json` между машинами. |
| В **AG Context** нет карточки ZCode и кнопки входа | — | Так и должно быть: `BuildConfig.ZCODE_ENABLED = false`, ZCode-клиента в этой сборке нет по замыслу. Поставьте **AG Widgets** (`dev.agwidgets`), если ваучеры и баланс нужны на телефоне. |
| Виджет Android не обновляется в фоне | На ПК: `python collector\collector.py --doctor --online` | Примените настройки батареи из раздела выше и сверьте топик на телефоне с выводом `--show-topic`. |
| Телефон показывает старый или чужой чат | `python collector\collector.py --doctor-json` (поле `annotations_dir`) | Смена чата дебаунсится примерно на 15–20 с. Откройте чат в Antigravity, чтобы появились annotation-файлы, и нажмите **⟳** на телефоне. |
| На телефоне устаревшие квоты ПК после смены топика | `python collector\collector.py --show-topic` | Очистите и сохраните `ntfy_topic` на телефоне: это сбрасывает кэш состояния ПК и старые алерты. Вставьте актуальный топик и нажмите **⟳**. |
| `antigravity_hook` или `antigravity_sidecar` в статусе `warn` | `python collector\collector.py --doctor` | Хук или sidecar не зарегистрирован либо скрипт не найден. Повторно запустите `install.ps1` — записи перерегистрируются без дублей. |
| `ntfy_reachability: fail` за корпоративным прокси или файрволом | `python collector\collector.py --doctor --online` | Разрешите исходящие запросы к `https://ntfy.sh` и `https://zcode.z.ai`; без них телефон не получит данные с ПК. |

## 5. Удаление и полная очистка

Сначала предпросмотр, затем удаление:

```powershell
.\install.ps1 -Uninstall -DryRun
.\install.ps1 -Uninstall
```

`-Uninstall` удаляет только записи ag-widgets: хук, sidecar, statusLine (прежнее значение восстанавливается из бэкапа установки) и ярлыки. Каталог `%LOCALAPPDATA%\ag-widgets` намеренно сохраняется:

```text
%LOCALAPPDATA%\ag-widgets\
  config.json     пороги, model_windows, python_path
  secrets.json    токен ZCode и ntfy-топик (шифртекст DPAPI)
  state\          context.json, status.json, agy_status.json, desktop_errors.log
  backups\        install-<timestamp>\ и uninstall-<timestamp>\
```

Полное удаление данных вместе с секретами:

```powershell
Remove-Item -LiteralPath "$env:LOCALAPPDATA\ag-widgets" -Recurse -Force
```

Вместе с каталогом удаляются токен ZCode и ntfy-топик. Сопряжение с телефоном после этого придётся выполнять заново. Эта же команда удаляет бэкапы в `backups\`; сохраните их, если позже понадобится восстановить чужой `statusLine`.

На Android удалите приложение — `dev.agwidgets` (**AG Widgets**) и/или `dev.agwidgets.context` (**AG Context**): долгое нажатие на иконку -> «О приложении» -> «Удалить». Сначала уберите виджеты с рабочего стола.

При желании удалите папку с клоном репозитория.

## См. также

- [INSTALL_GUIDE.md](INSTALL_GUIDE.md) — английская версия руководства.
- [AGENT_INSTALL.md](AGENT_INSTALL.md) — установка через ИИ-агента и таблица исправлений по проверкам doctor.
- [../README.ru.md](../README.ru.md) — описание проекта и архитектуры.
- [CONTEXT_HEALTH.md](CONTEXT_HEALTH.md) — про зоны здоровья контекста.

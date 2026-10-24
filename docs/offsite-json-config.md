# ТЗ: JSON-конфиги для offsite-протоколов (Xray-совместимый формат)

Статус: проект ТЗ. Реализация — в `qThroneMobile/`; Go-ядро `Throne/` менять не требуется (см. §7).

> Пользовательское руководство: `docs/offsite-json-config-guide.md`.

## 1. Цель

Дать пользователю один JSON-документ, в котором можно:

- объявить **несколько offsite-протоколов** (qWDTT, CSQTT, WDTT Plus, olcRTC, OpenFlux, DNSTT, VayDNS, SlipStream, MasterDNS, S3 Bypass, SSTP) как обычные outbound'ы;
- задать **маршрутизацию** (`routing.rules`) прямо в конфиге, как в Xray;
- задать **балансировку** (`routing.balancers`) прямо в конфиге, как в Xray.

Формат документа — **Xray-совместимый** (секции `outbounds`, `routing`, `dns`, `log`). Offsite-протоколы в `outbounds` описываются через `protocol` + `settings`, стандартные — как в Xray. При сборке конфиг транслируется в нативный конфиг ядра qThrone (sing-box `option.Options`), а offsite-клиенты запускаются существующим механизмом `CarrierProcess`/`CarrierPool`.

### Зафиксированные решения (согласовано с заказчиком)

1. Формат — **Xray-совместимый**.
2. Балансировка — **Xray `routing.balancers` транслируются в нативные группы** `auto-selector` / `urltest` / `selector`. Отдельный балансировщик в Go-ядре не пишем.
3. Охват — **все carrier-протоколы** (11 шт., см. §5.3). TrustTunnel — опционально как стандартный outbound (он встроен в sing-box, не offsite).
4. Модель — **новый тип профиля** «Offsite JSON Config», по образцу `Custom`/`fullconfig`, с JSON-редактором и схемой.
5. Балансировка — **вариант A: приближение нативными группами** (см. §6.3). Go-ядро `Throne/` не меняется; отличия от Xray документируются в UI и в §12.

## 2. Термины

- **Offsite-протокол** — протокол, который не реализован ни в sing-box, ни в Xray, а запускается отдельным нативным процессом (`carriers/*`, `lib*.so`) и подключается к ядру через аутентифицированный loopback-SOCKS5.
- **Профиль** — запись в таблице `profiles` (`ProxyEntity`), поле `type` + `outbound_json`.
- **Core config** — итоговый sing-box `option.Options` JSON, который потребляет `ThroneCore` (`Mobile.newInstance`).
- **Carrier plan** — `CarrierPlan` (свободный loopback-порт, случайные SOCKS-креды, приватный JSON для нативного клиента).
- **Группа** — нативный outbound ядра: `auto-selector`, `urltest`, `selector`.

## 3. Область работ

### Входит в объём

- Новый тип профиля `offsiteconfig` и его парсер/сериализатор.
- Парсер и валидатор Xray-совместимого документа.
- Трансляция `outbounds` / `routing.rules` / `routing.balancers` / `dns` в core config.
- Генерация `CarrierPlan` для каждого offsite-outbound и запуск клиентов до старта ядра.
- Импорт из буфера/файла, JSON-редактор со схемой, отображение в списке профилей.
- Тесты (unit + instrumented) и документация.

### Не входит в объём (v1)

- Пользовательские `inbounds` (tun/socks инжектит приложение; секция `inbounds` игнорируется).
- Xray-протоколы, которых нет в sing-box-ядре, кроме offsite (например, `splithttp`, `mKCP`, `xhttp`). Такие outbound'ы — ошибка валидации с понятным сообщением.
- Xray-специфичные алгоритмы `leastLoad`/`leastPing` в исходном виде — только приближение через нативные группы (§6.3).
- Подписки/шаринг конфига как отдельный формат подписки (можно позже).
- Паритет с десктопным qThrone C++ (только Android-клиент).
- Изменения Go-ядра.

## 4. Пользовательские сценарии

1. **Импорт**: пользователь вставляет JSON в «+» → приложение распознаёт offsite-outbound'ы и создаёт профиль `offsiteconfig`.
2. **Редактирование**: открывает профиль → «Edit as JSON» → редактор с подсветкой/схемой → сохранение с валидацией.
3. **Запуск**: приложение поднимает все offsite-клиенты, генерирует core config, стартует ядро.
4. **Маршрутизация**: `routing.rules` в конфиге решают, какой outbound/balancer использовать; встроенные профили роутинга приложения для этого профиля не применяются (конфиг самодостаточен).
5. **Балансировка**: трафик распределяется между offsite-outbound'ами согласно `routing.balancers`.
6. **Диагностика**: видно, какие клиенты поднялись, какие порты/теги, какие ошибки валидации.

## 5. Формат конфига

### 5.1 Общая структура

```jsonc
{
  "log": { "loglevel": "warning" },
  "dns": { /* Xray-совместимо, §5.7 */ },
  "inbounds": [ /* игнорируется */ ],
  "outbounds": [ /* §5.2, §5.4 */ ],
  "routing": {
    "domainStrategy": "AsIs",
    "rules": [ /* §5.5 */ ],
    "balancers": [ /* §5.6 */ ]
  }
}
```

Правила:

- `outbounds` обязателен, минимум 1 элемент; `tag` уникален.
- `routing` опционален. Если нет — весь трафик идёт в первый outbound (поведение Xray).
- `routing.final` (расширение сверх Xray, поддержано) — тег финального outbound'а или балансировщика. Приоритет: `routing.final` → первый outbound.
- `inbounds` допускается в документе для совместимости, но **вырезается** при сборке (как `Custom.fromXrayFullConfig`).
- Допускаются дополнительные мета-ключи `remarks`/`name` (имя профиля) — как в существующем импорте Xray.
- Неизвестные **секции верхнего уровня** — предупреждение (не ошибка), чтобы не ломать чужие экспорты. Неизвестные ключи внутри `outbounds`/`routing` — ошибка соответствующего элемента.

### 5.2 Offsite outbound

```jsonc
{
  "tag": "wdtt-1",
  "protocol": "qwdtt",
  "settings": {
    "server": "turn.example.com",
    "server_port": 56000,
    "password": "…",
    "hashes": ["…"],
    "mode": "wg",
    "workers": 30
  }
}
```

- `protocol` — идентификатор типа из таблицы §5.3.
- `settings` — **тот же JSON, что хранится в `outbound_json` существующего профиля** этого типа. Разбор выполняет `OutboundFactory.newByType(protocol).parseFromJson(settings)`. Это гарантирует отсутствие второй схемы полей. Транслятор предварительно добавляет в `settings` поля `type` = `protocol` и `name` = `tag`, если их нет (существующие `parseFromJson` этого требуют).
- `tag` — обязателен; при отсутствии `settings.name` имя профиля берётся из `tag`.
- `streamSettings` для offsite **не используется** (транспорт задаётся внутри `settings`: `transport`, `mode`, `turn_tcp`, `sni` и т.п.). Если присутствует — предупреждение.
- Секреты (`password`, `hashes`, `token`, `key`, `accessKey`/`secretKey`) не должны попадать в логи (см. §11).

### 5.3 Поддерживаемые offsite-протоколы

| `protocol` | Тип профиля | Ключевые поля `settings` (полный набор — `parseFromJson` типа) |
|---|---|---|
| `qwdtt` | `Qwdtt` | `server`, `server_port`, `password`, `hashes[]`, `mode` (`wg`/`raw`), `hash_source`, `raw_port`, `workers`, `turn_tcp`, `obfs`, `go_dns`, `device_id`, `vk_anon_path`, `captcha_mode` |
| `csqtt` | `Csqtt` | всё из `qwdtt` + `vk_access_token`, `turn_transport` (`udp`/`tcp_tls`), `srtp_mode`, `fingerprint`, `client_ids`, `custom_turn_ips` |
| `wdttplus` | `WdttPlus` | всё из `qwdtt` + `wg_port`, `local_port`, `max_workers`, `tcp_only`, `turn_sni`, `rt_masque`, `rt_masque_server_bootstrap`, `vk_calls_preflight`, `legacy` |
| `olcrtc` | `OlcRtc` | `provider`, `transport`, `room`, `key` (64 hex), `token`, `channel`, `dns`, `device_id`, `options{}` |
| `openflux` | `OpenFlux` | `transports[]`, `secret`, `negotiate`, `mode`, `codec`, `context` |
| `dnstt` | `DnsTunnel("dnstt")` | `domain`, `pubkey`, `record_type`, `dnstt_compat`, `clientid_size`, `idle_timeout`, `keepalive`, `authoritative`, `congestion_control`, `keep_alive_interval`, `resolvers[]`, `backend{}` |
| `vaydns` | `DnsTunnel("vaydns")` | `domain`, `cert`/`mkey`, `record_type`, `resolvers[]`, `backend{}` |
| `slipstream` | `DnsTunnel("slipstream")` | `domain`, `cert`, `resolvers[]`, `backend{}` |
| `masterdns` | `DnsTunnel("masterdns")` | `domain`, `mkey`, `record_type`, `options{}`, `resolvers[]` |
| `s3bypass` | `S3Bypass` | `storage{type,endpoint,region,bucket,prefix,sessionsDir,accessKey,secretKey}`, `tuning{pollIntervalMs,writeIntervalMs,idleTimeoutSec,maxFileSizeBytes}` |
| `sstp` | `Sstp` | `server`, `server_port`, `username`, `password`, `sni`, `allow_insecure` |
| `trusttunnel` *(опц.)* | `TrustTunnel` | `server`, `server_port`, `username`, `password`, `congestion_control`, `custom_sni`, `client_random`, `health_check`, `quic`, `tls` |

`trusttunnel` обрабатывается как **стандартный** outbound ядра (не через `CarrierProcess`), но допускается в том же документе.

### 5.4 Стандартные outbound'ы

**Ограничение v1:** стандартные (не offsite) outbound'ы задаются в **sing-box-форме** `settings` — поля, которые разбирают модели приложения (`server`, `server_port`, `uuid`, `password`, `tls`, `transport` и т.д.). Xray-форма (`vnext`/`servers`/`streamSettings`) в v1 **не конвертируется**: такой outbound даёт понятную ошибку. Конвертация Xray-формы — Phase 3 (§13).

Это ограничение не касается offsite-протоколов: их `settings` — родной JSON приложения (§5.2), а `routing`/`balancers` — Xray-синтаксис.

Разрешаются протоколы, которые ядро qThrone (sing-box fork) умеет нативно:

| `protocol` | Что | `settings` (sing-box-форма) |
|---|---|---|
| `freedom` / `direct` | напрямую | — |
| `blackhole` / `block` | блокировка | — |
| `socks`, `http` | socks/http | `server`, `server_port`, `username`, `password` |
| `shadowsocks` | Shadowsocks | `method`, `password` |
| `vmess` | VMess | `uuid`, `alter_id`, `security` |
| `vless` | VLESS | `uuid`, `flow`; `tls`/`transport` |
| `trojan` | Trojan | `password`; `tls`/`transport` |
| `hysteria` / `hysteria2` / `hy2` | Hysteria(2) | — |
| `tuic` | TUIC | — |
| `juicity` | Juicity | — |
| `anytls` | AnyTLS | — |
| `shadowtls` | ShadowTLS | — |
| `mieru` | Mieru | `transport`, `username`, `password`, `server_ports` |
| `snell` | Snell | — |
| `naive` | NaïveProxy | — |
| `trusttunnel` | TrustTunnel | — |
| `wireguard` | WireGuard / **AmneziaWG** | **endpoint**: `private_key`, `address`, `peers`; Amnezia — `amnezia_wg{jc,jmin,jmax,s1,s2,h1..h4}` |
| `masque` | MASQUE | **endpoint** |
| `openvpn` | OpenVPN | **endpoint** |
| `openconnect` | OpenConnect | **endpoint** |

**Протоколы-эндпоинты** (`wireguard`, `masque`, `openvpn`, `openconnect`) попадают в секцию `endpoints` итогового core-конфига, а не в `outbounds`. В правилах и балансировщиках на них ссылаются по `tag` так же, как на обычные outbound'ы.

Для стандартных outbound'ов v1 использует `settings.tls` / `settings.transport` (sing-box-форма); `streamSettings` **не учитывается** и даёт предупреждение. Неподдерживаемый протокол → ошибка валидации.

### 5.5 `routing.rules`

Поддерживаются правила Xray вида:

```jsonc
{ "type": "field", "domain": ["geosite:category-ads-all"], "outboundTag": "block" }
{ "type": "field", "ip": ["geoip:ru"], "outboundTag": "direct" }
{ "type": "field", "network": "tcp,udp", "balancerTag": "auto" }
```

Маппинг полей:

| Xray | Core (sing-box) |
|---|---|
| `outboundTag` | `outbound` |
| `balancerTag` | `outbound` = тег сгенерированной группы |
| `inboundTag` | `inbound` |
| `domain[]`: `full:x` | `domain: ["x"]` |
| `domain[]`: `domain:x` | `domain_suffix: ["x"]` |
| `domain[]`: `keyword:x` | `domain_keyword: ["x"]` |
| `domain[]`: `regexp:x` | `domain_regex: ["x"]` |
| `domain[]`: `geosite:x` | `rule_set: ["geosite-x"]` (через `RuleSetCatalog`/`ProviderGeoAssets`) |
| `ip[]`: CIDR | `ip_cidr` |
| `ip[]`: `geoip:x` | `rule_set: ["geoip-x"]` |
| `port` (`"80,443"`, `"1000-2000"`) | `port`, `port_range` |
| `source` | `source_ip_cidr` |
| `sourcePort` | `source_port`, `source_port_range` |
| `network` (`tcp`/`udp`/`tcp,udp`) | `network` |
| `protocol` | `protocol` |
| `user[]` | `auth_user` (best-effort) |
| `attrs` | не поддерживается → предупреждение |
| `type` (кроме `field`) | не поддерживается → ошибка |
| `ruleTag` | игнорируется |

- Порядок правил сохраняется (первое совпадение выигрывает).
- `routing.domainStrategy`: `AsIs` → без резолвера; `IPIfNonMatch`/`IPOnDemand` → выставить `route.default_domain_resolver` на первый DNS-сервер и `domain_strategy` у outbound'ов, где ядро это поддерживает; иначе — предупреждение.
- `routing.domainMatcher` игнорируется.
- Ссылка на несуществующий `outboundTag`/`balancerTag` → ошибка.
- `routing.final` (расширение сверх Xray) задаёт `route.final`; при отсутствии — первый outbound.

### 5.6 `routing.balancers`

```jsonc
{
  "tag": "auto",
  "selector": ["wdtt-", "csqtt-"],
  "strategy": { "type": "leastPing" },
  "fallbackTag": "direct"
}
```

- `selector` — список **префиксов тегов**; в группу попадают все outbound'ы, чей `tag` начинается с любого префикса. Должен найти ≥1 outbound.
- `strategy.type`: `random`, `leastPing`, `leastLoad` (см. §6.3).
- `strategy.settings` (Xray): `expected`, `maxRTT`, `tolerance`, `basetime` — маппятся в поля группы, где применимо.
- `fallbackTag` — резервный outbound, если в группе нет живых участников. Реализация — best-effort (§6.3).
- Запрещены: пустой `selector`, пересечение тегов групп, ссылка группы на саму себя, `balancerTag`, указывающий на не-балансировщик.

### 5.7 `dns`

Поддерживается best-effort:

- `dns.servers[]` (строка или объект `{address, domains, expectIPs, skipFallback}`) → `dns.servers` с `tag`; `address` разбирается в `type`/`server`/`server_port` (udp/tcp/tls/https).
- `dns.hosts` → статические записи (через hosts-сервер ядра, если доступно; иначе предупреждение).
- `dns.queryStrategy` → `dns.strategy`.
- `dns.rules` не поддерживается в v1 → предупреждение.
- Если `dns` отсутствует — используются DNS-настройки приложения (как в ветке `fullconfig`).

### 5.8 `inbounds`

Секция допускается, но **полностью вырезается**. Приложение само инжектит tun/socks inbound и DNS-обвязку (переиспользуется ветка `Custom.fullconfig` в `ConfigGenerator`).

## 6. Трансляция в core config

Точка входа — новая ветка в `ConfigGenerator.build()` (аналог `isFullConfig()`), которая:

1. Парсит документ в модель `OffsiteConfig`.
2. Создаёт `CarrierPlan` для каждого offsite-outbound (свободный TCP-порт, UDP-порт если нужен, случайные креды).
3. Заменяет offsite-outbound на `{"type":"socks","server":"127.0.0.1","server_port":P,"version":"5","username":…,"password":…,"tag":<исходный tag>}`.
4. Транслирует стандартные outbound'ы.
5. Строит `route.rules`, группы балансировки, `route.final`, `dns`.
6. Инжектит tun inbound + дефолты (как `fullconfig`), применяет `VpnAppGuard`.
7. Кладёт планы в `GeneratedConfig.carrierPlans`; `BoxInstance.init()` поднимает их **до** `Mobile.newInstance` (существующий порядок).

### 6.1 Outbound'ы

- Offsite → `socks` (см. выше), `tag` сохраняется.
- Стандартные → соответствующий core-тип.
- Дубли `tag` → ошибка.
- Offsite-клиенты, чей `privateConfig` не проходит валидацию (как в `NativeTransportSettingsActivity`), → ошибка профиля с указанием тега.

### 6.2 Правила

1:1 по таблице §5.5. `geosite:`/`geoip:` конвертируются в `rule_set` через существующие `ProviderGeoAssets`/`RuleSetCatalog`; при недоступности ассетов — ошибка сборки с понятным текстом (как сейчас для route-профилей).

### 6.3 Балансировка → нативные группы

**Вариант A (зафиксирован): балансировка реализуется нативными группами ядра, без изменений Go-кода.**

| Xray `strategy.type` | Группа ядра | Параметры | Точность |
|---|---|---|---|
| `random` | `auto-selector` | `balance: true`, `balance_mode: "connection"`, `balance_interval` = `settings.basetime` (30s), `expected`/`active_size` из settings | близко: выбор на соединение; распределение может отличаться от равномерно случайного |
| `leastPing` | `urltest` | `url` = глобальный test URL, `interval` = `settings.basetime` (300s), `tolerance` = `settings.tolerance` (50ms) | практически эквивалентно |
| `leastLoad` | `auto-selector` | `balance: false`, `expected` (3), `active_size` (8), `tolerance` (300ms), `max_rtt` = `settings.maxRTT` (0 = без потолка) | приближение: RTT + health, **без метрики нагрузки** |
| (нет `strategy`) | `auto-selector` | как `random` — Xray по умолчанию использует `random` | близко |

Дефолты транслятора (фиксируются константами и показываются в UI):

- `expected` = 3, `maxRTT` = 0 (без потолка), `tolerance` = 50 ms для `leastPing` и 300 ms для `leastLoad`;
- `basetime` = 30 s для `random`/`leastLoad`, 300 s для `leastPing`;
- `balance_interval` = `basetime`.

Замечание по режиму `random`: целевой режим — `connection` (Xray выбирает узел на соединение). При реализации сверить фактическое поведение `balance_mode` в ядре; если `connection` не даёт per-connection выбор, использовать `rotate` и зафиксировать это в §12.

Дополнительно:

- Группа получает `tag` = `balancer.tag`.
- `fallbackTag`: добавляется последним участником группы. `route.final` по умолчанию — первый outbound (как в Xray), если не задан `routing.final`; отдельного «мягкого» fallback у нативных групп нет. **Ограничение фиксируется в UI/доках.**
- Группы не могут участвовать в `detour`-цепочках как промежуточный хоп (как и `auto-selector` сейчас).

### 6.4 DNS

По §5.7. При отсутствии/ошибке — дефолтный DNS приложения.

## 7. Архитектура и точки интеграции

Go-ядро **не меняется**: используются уже существующие `auto-selector` (с `balance`) и `urltest`/`selector`, а offsite-клиенты — существующий `CarrierProcess`/`CarrierPool`.

| Слой | Файлы (ориентир) | Изменение |
|---|---|---|
| Модель профиля | `app/src/main/java/io/nekohasekai/sagernet/outbound/types/OffsiteConfig.kt` (новый) | `Outbound("offsiteconfig")`, поле `config: String`, `parseFromJson`/`exportToJson`, `displayType`/`displayAddress` |
| Реестр типов | `outbound/OutboundFactory.kt` | Регистрация `offsiteconfig`; `PROTOCOLS` — распознавание документов |
| Парсер/валидатор | `outbound/offsite/OffsiteConfigParser.kt`, `OffsiteConfigValidator.kt` (новые) | Разбор Xray-документа в модель, ошибки/предупреждения |
| Трансляция | `outbound/offsite/OffsiteTranslator.kt` (новый) | outbounds/rules/balancers/dns → core config |
| Генератор | `outbound/config/ConfigGenerator.kt` | Новая ветка для `offsiteconfig` (рядом с `fullconfig`) |
| Планы клиентов | `bg/carrier/CarrierPlan.kt` | Обобщить `CarrierPlan.qwdtt` → `CarrierPlan.native(id, carrier)`; синтетические id из тегов |
| Запуск | `bg/proto/BoxInstance.kt` | Без изменений (планы уже поднимаются до ядра) |
| Импорт | `outbound/import/ProfileImport.kt` | Детект: `outbounds` с offsite-`protocol` → `offsiteconfig`, а не `xrayfullconfig` |
| Настройки | `ui/profile/ProfileSettingsRouter.kt` | `offsiteconfig` → новый экран |
| Экран/редактор | `ui/profile/OffsiteConfigSettingsActivity.kt` (новый), `ui/json/JsonEditorActivity.kt` | Просмотр + «Edit as JSON» |
| Схема | `app/src/main/assets/schema/offsite-config.json` (новый) | JSON Schema для подсветки/валидации в редакторе |
| Тесты | `app/src/test/.../offsite/*` | Unit-тесты |

### Хранение

- Тип профиля: `type = "offsiteconfig"`.
- `outbound_json` = `{"name": "...", "type": "offsiteconfig", "config": "<текст документа>"}` — по образцу `Custom`.
- **Миграция БД не нужна**: новых колонок нет, `profiles.type` — свободная строка, `outbound_json` уже есть.

## 8. Импорт, UI, схема

### Импорт

- «+» → «Импорт из буфера/файла» и авто-детект в `ProfileImport.Parser.json(...)`:
  - документ с `outbounds[]`, где хотя бы один `protocol` ∈ offsite-набор → `offsiteconfig`;
  - иначе прежняя логика (`xrayfullconfig` / sing-box).
- Имя профиля: `remarks` → `name` → первый `tag` → «Offsite Config».
- Дубликаты тегов/невалидный JSON → отказ с текстом ошибки, профиль не создаётся.

### Экран настроек

- Заголовок: «Offsite JSON Config».
- Краткая сводка: число outbound'ов, число offsite, число правил, число балансировщиков.
- Список тегов с типом и отметкой «offsite».
- Кнопка «Edit as JSON» → `JsonEditorActivity` с `offsite-config.json`.
- Кнопка «Проверить» → прогон парсера/валидатора без сохранения.
- Ошибки/предупреждения показываются списком с путём (`outbounds[2].settings.hashes`).

### Список профилей

- `displayType()` = «Offsite Config».
- `displayAddress()` = первый egress-тег (или `<N> outbounds`).
- Иконка — как у custom-конфигов.

### Схема

В v1 редактор работает в режиме **проверки синтаксиса** (штатная sing-box-схема не описывает этот документ), а семантику проверяет кнопка «Проверить» (`OffsiteConfigValidator`). Отдельный `offsite-config.json` (draft-07) с перечислением секций/протоколов — follow-up (§13).

## 9. Валидация

Обязательные проверки (блокируют сохранение/запуск):

1. Корректный JSON-объект.
2. `outbounds` — непустой массив; у каждого уникальный непустой `tag`.
3. `protocol` известен; для offsite `settings` проходит `parseFromJson` + `privateConfig(1080, 1081, testCreds)`.
4. `balancers[].selector` находит ≥1 outbound; нет циклов/самоссылок.
5. Все `outboundTag`/`balancerTag` в правилах существуют.
6. Стандартные outbound'ы: протокол и транспорт поддерживаются ядром.
7. Размер документа ≤ 1 МиБ; число outbound'ов ≤ 256; число правил ≤ 2048.

Предупреждения (не блокируют):

- неизвестная секция верхнего уровня;
- `streamSettings` у offsite;
- `attrs`, `dns.rules`, `domainMatcher`, `user`;
- `fallbackTag` со стратегией без мягкого fallback.

## 10. Тестирование

Unit (JVM):

- парсер: все 11 протоколов + стандартные outbound'ы;
- маппинг правил (все поля таблицы §5.5), включая `geosite:`/`geoip:`;
- маппинг балансировщиков (3 стратегии, settings, fallback);
- валидатор: негативные кейсы (дубли тегов, битые ссылки, неизвестный протокол, превышение лимитов);
- генерация `CarrierPlan`: число планов, уникальность портов/кредов, детерминизм ключа;
- редакция секретов в логах.

Instrumented / ручное:

- импорт реального Xray-документа с offsite-outbound'ами;
- запуск каждого carrier'а на устройстве/эмуляторе (хотя бы qWDTT, olcRTC, SSTP, DNSTT);
- проверка, что трафик идёт через нужный outbound/balancer (логи ядра + `QueryAutoSelectors`);
- поведение при падении одного клиента (failover/`expected`).

Регресс:

- существующие `fullconfig`, `xrayfullconfig`, обычные профили и route-профили не меняются.

## 11. Безопасность

- Приватные JSON нативных клиентов — только в app-private `cacheDir/carriers/session-<uuid>/config.json`, `0600`, удаляются после остановки (существующее поведение).
- SOCKS-слушатели — только `127.0.0.1`, обязательная аутентификация (существующий `socksReady`).
- Секреты из `settings` не логируются (`CarrierLogRedactor`); в сообщениях об ошибках — только путь к полю, без значения.
- Конфиг хранится в БД приложения (приватная), не экспортируется автоматически.
- При «поделиться» — явное предупреждение, что документ содержит секреты.

## 12. Ограничения и совместимость

- Профиль **самодостаточен**: встроенные профили роутинга приложения и настройки группы для него не применяются (маршрутизация — из JSON).
- Профиль **не участвует в цепочках** как хоп (аналог `fullconfig`/`xrayfullconfig`): это конфиг целиком. В будущем — опционально как финальный хоп.
- **Пинг** профиля измеряется реальным ядром по его основному egress (`routing.final`, иначе первый outbound/группа) в отдельном тест-боксе; правила маршрутизации в тест-бокс не попадают (на dial не влияют), carrier-планы поднимаются перед пробой.
- Балансировка — **вариант A** (см. §6.3): `leastPing` практически эквивалентен (`urltest`), `random` близок (`auto-selector`), `leastLoad` — приближение по RTT/здоровью **без метрики нагрузки**. Точные алгоритмы Xray не воспроизводятся. Go-ядро не меняется.
- `fallbackTag` — best-effort: участник добавляется в группу последним; при отсутствии явного финального правила `route.final` = `fallbackTag`. Мягкий fallback зависит от стратегии.
- `inbounds` игнорируются; пользовательские DNS-правила (`dns.rules`) — v2.
- Xray-протоколы вне набора ядра — ошибка. Стандартные outbound'ы в v1 принимаются только в sing-box-форме `settings`; Xray-форма (`vnext`/`servers`/`streamSettings`) не конвертируется (Phase 3).

## 13. План реализации

1. **Модель и парсер** (`OffsiteConfig`, `OffsiteConfigParser`, `OffsiteConfigValidator`) + unit-тесты.
2. **Трансляция** outbounds → core + `CarrierPlan` (обобщить `CarrierPlan.native`).
3. **Routing/balancers/DNS** → core config + тесты.
4. **Интеграция** в `ConfigGenerator`, `ProfileImport`, `OutboundFactory`.
5. **UI**: экран, редактор, схема, отображение в списке.
6. **Тесты на устройстве**, документация (`docs/offsite-json-config.md` для пользователя), обновление `README.md`.
7. **Phase 3:** конвертация Xray-формы стандартных outbound'ов (`vnext`/`servers`/`streamSettings` → sing-box).
8. **Follow-up:** JSON Schema `offsite-config.json` для автодополнения в редакторе, экспорт/шаринг.

## 14. Критерии приёмки

1. Документ из §15 импортируется и запускается; трафик идёт через указанный балансировщик.
2. Все 11 offsite-протоколов могут быть объявлены в одном конфиге и одновременно работать (при совместимых настройках).
3. `routing.rules` (домены/IP/порты/сеть/гео) применяются как в Xray.
4. `routing.balancers` трёх стратегий работают через нативные группы; `fallbackTag` — согласно §6.3.
5. Ошибки валидации понятны и указывают путь в документе.
6. Существующие типы профилей и сборка не регрессируют.
7. Go-ядро `Throne/` не изменено.

## 15. Пример

```jsonc
{
  "remarks": "Offsite mix",
  "log": { "loglevel": "warning" },
  "outbounds": [
    {
      "tag": "wdtt-1",
      "protocol": "qwdtt",
      "settings": {
        "server": "turn1.example.com",
        "server_port": 56000,
        "password": "secret",
        "hashes": ["hash1", "hash2"],
        "mode": "wg",
        "workers": 30
      }
    },
    {
      "tag": "wdtt-2",
      "protocol": "qwdtt",
      "settings": {
        "server": "turn2.example.com",
        "server_port": 56000,
        "password": "secret",
        "hashes": ["hash1"],
        "mode": "wg"
      }
    },
    {
      "tag": "sstp-1",
      "protocol": "sstp",
      "settings": {
        "server": "vpn.example.com",
        "server_port": 443,
        "username": "user",
        "password": "pass",
        "sni": "vpn.example.com",
        "allow_insecure": false
      }
    },
    { "tag": "direct", "protocol": "freedom" },
    { "tag": "block", "protocol": "blackhole" }
  ],
  "routing": {
    "domainStrategy": "AsIs",
    "rules": [
      { "type": "field", "domain": ["geosite:category-ads-all"], "outboundTag": "block" },
      { "type": "field", "ip": ["geoip:ru"], "outboundTag": "direct" },
      { "type": "field", "network": "tcp,udp", "balancerTag": "auto" }
    ],
    "balancers": [
      { "tag": "auto", "selector": ["wdtt-"], "strategy": { "type": "leastPing" }, "fallbackTag": "sstp-1" }
    ]
  }
}
```

## 16. Решения и финальные вопросы

### Зафиксировано

- Балансировка — вариант A (приближение), §6.3; Go-ядро не меняется.
- При отсутствии `strategy` используется `random` (как в Xray).
- `fallbackTag` — best-effort: последний участник группы + `route.final` при отсутствии явного финального правила.
- Профиль самодостаточен: встроенные route-профили приложения для него не применяются.
- `inbounds` вырезаются; tun/socks инжектит приложение.
- **`routing.final`** — поддержан как расширение сверх Xray (§5.1, §5.5).
- **DNS в v1** — `dns.servers` + `queryStrategy`; `dns.rules`/`dns.hosts` — предупреждение, используются DNS-настройки приложения там, где не покрыто.
- **Xray-only outbound'ы** (splithttp, mKCP, xhttp и т.п.) — отклоняются в v1 с понятной ошибкой.
- **Цепочки** — профиль в v1 в цепочках не участвует (как `fullconfig`).
- **Шаринг/экспорт** — в v1 отсутствует; только импорт, редактирование, запуск.

### Итог

Открытых вопросов нет. ТЗ готово к реализации по плану §13.

## 17. Статус реализации

| Этап | Состояние | Артефакты |
|---|---|---|
| Phase 1 — модель/парсер/валидатор | Готово | `outbound/types/OffsiteConfig.kt`, `outbound/offsite/{OffsiteModels,OffsiteProtocols,OffsiteConfigParser,OffsiteConfigValidator}.kt`, регистрация в `OutboundFactory`; 29 тестов |
| Phase 2 — трансляция в core config | Готово | `outbound/offsite/OffsiteTranslator.kt`, ветка `buildOffsiteConfig` в `ConfigGenerator`, `CarrierPlan.native`, `TypeAccess.asOffsiteConfig`; 12 тестов |
| Phase 3 — импорт и UI | Готово | детект offsite в `ProfileImport`, `OffsiteConfigSettingsActivity` + `res/xml/offsite_config_preferences.xml`, пункт «+» (`add_profile_menu.xml`, `MobileHomeFragment`, `ProfileImports`), `ProfileSettingsRouter`, манифест; 4 теста |

Проверки: 61 тест offsite/import — зелёные; `:app:assembleOssDebug` собирается (APK arm64-v8a / armeabi-v7a / x86_64 / universal). Полный прогон unit-тестов: 400 тестов, 1 падение — `WdttPlusSshInputTest.longRootScriptsTravelInStdinAndRemainExecutable`, не связано с фичей (локальный `sudo` в окружении, код `deploy` не менялся).

Осталось (см. §13): конвертация Xray-формы стандартных outbound'ов, JSON Schema для редактора, проверка на устройстве с реальными carrier-клиентами.

### Исправления после аудита

- **Xray `log`** (`loglevel`/`access`/…) больше не пишется в core `log` дословно: `loglevel`→`level`, `none`→`disabled`; неизвестные поля отбрасываются (иначе `DisallowUnknownFields` валит запуск).
- **`fallbackTag`** валидируется (существование, не самоссылка) и не добавляется в группу, если тег неизвестен.
- **Одинаковые carrier-настройки** дедуплицируются в один `CarrierPlan` (иначе `CarrierPool` падал на втором acquire).
- **`ProfileValidator`** знает `offsiteconfig` — «Удалить нерабочие» больше не удаляет offsite-профили.
- **Дубликаты DNS-тегов** и **некорректные порты/CIDR** — ошибки валидации; неизвестные схемы DNS игнорируются с предупреждением.
- **Пустая `dns.servers`** больше не подавляет DNS приложения.
- Добавлены **head-правила** `sniff`/`hijack-dns` (и `resolve` при доменной стратегии), иначе доменные правила не срабатывали.
- Поддержан алиас **`wdtt`**.
- `routing.domainStrategy`/`domainMatcher` — предупреждение.

### Пинг offsite-профилей

Offsite-профили тестируются как отдельный тест-бокс ядра (`ConfigGenerator.buildTest`): собирается egress-граф документа (outbounds/endpoints/группы) с DNS приложения (прямой резолвер, чтобы адрес сервера туннеля не резолвился через сам туннель) и явным `route.final`; `inbounds` пустые, правила маршрутизации не переносятся. Carrier-планы пробы поднимаются через `TestProbe`/`CarrierPool` (новое поле `GeneratedConfig.fullConfigCarrierPlans`). Задержка в списке — это задержка основного egress (`route.final`, иначе первый outbound/группа).

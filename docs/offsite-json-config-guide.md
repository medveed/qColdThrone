# qColdThrone: JSON-конфиги offsite-протоколов — руководство

Это практическое руководство по новому типу профиля **«Offsite JSON config»**: один JSON-документ, в котором можно
объявить несколько offsite-протоколов (qWDTT, CSQTT, WDTT Plus, olcRTC, OpenFlux, DNSTT/VayDNS/SlipStream/MasterDNS,
S3 Bypass, SSTP), задать маршрутизацию и балансировку в стиле Xray — и подключиться одним профилем.

Техническое ТЗ лежит рядом: `docs/offsite-json-config.md`.

---

## 1. Когда это нужно

- Несколько внешних протоколов в одном профиле.
- Автоматическое переключение/балансировка между ними.
- Маршрутизация (какие домены/IP куда) прямо внутри конфига, без отдельного профиля роутинга.

Если нужен один обычный профиль — используйте обычные экраны. Offsite-конфиг самодостаточен: он заменяет собой
и профиль, и маршрутизацию.

---

## 2. Быстрый старт

### Создать

1. На главном экране нажмите **«+»**.
2. В списке типов выберите **«Offsite JSON config»** (или в меню добавления — тот же пункт).
3. Впишите **имя** профиля.
4. Нажмите на поле **JSON** и вставьте документ.
5. Сохраните профиль (кнопка сохранения/назад).

### Импортировать готовый документ

- **Из буфера обмена/файла/по ссылке:** вставьте JSON. Приложение распознает документ как offsite-конфиг, если в
  `outbounds` есть хотя бы один offsite-протокол, и создаст профиль автоматически.
- Обычные Xray/sing-box конфиги продолжают импортироваться как раньше — конфликтов нет.

### Проверить конфиг

На экране профиля есть **«Check config»** — прогоняет парсер и валидатор без сохранения и показывает список
ошибок/предупреждений с путём (`outbounds[2].settings.password`). Сводка сверху показывает число outbound'ов,
offsite-протоколов, правил и балансировщиков.

Сам редактор проверяет **синтаксис** JSON. Семантику (уникальность тегов, ссылки, лимиты) проверяет «Check config»
и запуск.

### Подключиться

Выберите профиль как активный и подключитесь обычным способом (VPN или прокси-режим). Приложение само поднимет
все offsite-клиенты и запустит ядро.

> Пинг профиля измеряет его **основной egress** (`routing.final`, иначе первый outbound или группу) через реальное
> ядро — то есть тот выход, через который реально идёт трафик. Профиль **нельзя** использовать как звено цепочки.

---

## 3. Структура конфига

```jsonc
{
  "remarks": "Мой набор",          // имя профиля (необязательно)
  "log": { "loglevel": "warning" }, // необязательно
  "dns": { /* серверы DNS */ },      // необязательно
  "inbounds": [ /* игнорируется */ ],
  "outbounds": [ /* протоколы */ ],
  "routing": {
    "rules":     [ /* правила маршрутизации */ ],
    "balancers": [ /* группы балансировки */ ],
    "final": "direct"               // куда всё остальное (необязательно)
  }
}
```

| Секция | Обязательна | Что делает |
|---|---|---|
| `outbounds` | **да**, ≥1 | список протоколов (`tag` + `protocol` + `settings`) |
| `routing.rules` | нет | маршрутизация трафика |
| `routing.balancers` | нет | группы балансировки/фейловера |
| `routing.final` | нет | outbound по умолчанию (иначе — первый из `outbounds`) |
| `dns` | нет | свои DNS-серверы (иначе DNS приложения) |
| `log` | нет | уровень лога |
| `inbounds` | нет | **игнорируется**: приложение само добавляет tun/socks |
| `remarks` / `name` | нет | имя профиля |

### `outbounds[]`

```jsonc
{
  "tag": "wdtt-1",          // уникальное имя, обязательно
  "protocol": "qwdtt",      // см. список поддерживаемых
  "settings": { /* поля протокола */ }
}
```

Ключевой момент: **`settings` — это ровно тот JSON, что хранится у обычного профиля этого типа.**

Как получить готовый `settings`:

1. Создайте обычный профиль нужного протокола (или импортируйте `qwdtt://`, `sstp://` и т.д.).
2. Откройте его и выберите **«Raw JSON»**.
3. Скопируйте объект и вставьте его в `settings`.

Поля `type` и `name` в `settings` можно не указывать — приложение подставит их само (`type` = `protocol`,
`name` = `tag`).

---

## 4. Поддерживаемые протоколы

| `protocol` | Что это | Обязательные поля `settings` (кратко) |
|---|---|---|
| `qwdtt` | qWDTT | `server`, `server_port`, `password`, `hashes` |
| `csqtt` | CSQTT | как qWDTT + `vk_access_token`, `turn_transport` |
| `wdttplus` | WDTT Plus | как qWDTT + `rt_masque`, `max_workers` |
| `olcrtc` | olcRTC | `provider`, `transport`, `room`, `key` |
| `openflux` | OpenFlux | `transports`, `secret` (или `mode`) |
| `dnstt` | DNSTT | `domain`, `pubkey` |
| `vaydns` | VayDNS | `domain`, `cert`/`mkey` |
| `slipstream` | SlipStream | `domain`, `cert` |
| `masterdns` | MasterDNS | `domain`, `mkey` |
| `s3bypass` | S3 Bypass | `storage{endpoint,bucket,accessKey,secretKey}` |
| `sstp` | SSTP | `server`, `server_port`, `username`, `password` |

### Примеры `outbounds`

**qWDTT**

```jsonc
{
  "tag": "wdtt-1",
  "protocol": "qwdtt",
  "settings": {
    "server": "turn.example.com",
    "server_port": 56000,
    "password": "секрет",
    "hashes": ["hash1", "hash2"],
    "mode": "wg",
    "workers": 30
  }
}
```

**SSTP**

```jsonc
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
}
```

**DNSTT / VayDNS / SlipStream / MasterDNS**

```jsonc
{
  "tag": "dns-1",
  "protocol": "vaydns",
  "settings": {
    "domain": "t.example.com",
    "cert": "ПУБЛИЧНЫЙ_КЛЮЧ",
    "resolvers": ["1.1.1.1", "8.8.8.8"]
  }
}
```

**olcRTC**

```jsonc
{
  "tag": "rtc-1",
  "protocol": "olcrtc",
  "settings": {
    "provider": "jitsi",
    "transport": "datachannel",
    "room": "https://meet.example.com/room",
    "key": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
  }
}
```

**S3 Bypass**

```jsonc
{
  "tag": "s3-1",
  "protocol": "s3bypass",
  "settings": {
    "storage": {
      "type": "s3",
      "endpoint": "s3.example.com",
      "bucket": "bucket",
      "accessKey": "AK",
      "secretKey": "SK"
    }
  }
}
```

### Стандартные outbound'ы (не offsite)

В том же документе можно объявить обычные выходы — в **sing-box-форме** `settings`:

| `protocol` | Что |
|---|---|
| `freedom` / `direct` | напрямую |
| `blackhole` / `block` | блокировка |
| `socks`, `http`, `shadowsocks`, `vmess`, `vless`, `trojan` | обычные прокси |
| `hysteria2`/`hy2`/`hysteria`, `tuic`, `juicity`, `anytls`, `shadowtls`, `mieru`, `snell`, `naive`, `trusttunnel` | обычные прокси |
| `wireguard` | WireGuard и **AmneziaWG** (это `endpoint`) |
| `masque`, `openvpn`, `openconnect` | тоже `endpoint` |

```jsonc
{ "tag": "direct", "protocol": "freedom" }
{ "tag": "block",  "protocol": "blackhole" }
{ "tag": "ss-1", "protocol": "shadowsocks",
  "settings": { "server": "1.2.3.4", "server_port": 8388, "method": "aes-256-gcm", "password": "p" } }
```

**Mieru:**

```jsonc
{ "tag": "mieru-1", "protocol": "mieru",
  "settings": { "server": "203.0.113.9", "server_port": 443,
                "transport": "TCP", "username": "u", "password": "p" } }
```

**AmneziaWG** (`protocol: "wireguard"`, обфускация — в `amnezia_wg`):

```jsonc
{ "tag": "awg-1", "protocol": "wireguard",
  "settings": {
    "private_key": "<base64>",
    "address": ["10.0.0.2/32"],
    "peers": [ { "public_key": "<base64>", "address": "203.0.113.5", "port": 51820,
                 "allowed_ips": ["0.0.0.0/0"] } ],
    "amnezia_wg": { "jc": 4, "jmin": 40, "jmax": 70, "s1": 0, "s2": 0,
                    "h1": "1", "h2": "2", "h3": "3", "h4": "4" }
  } }
```

> `wireguard`, `masque`, `openvpn`, `openconnect` — это sing-box **endpoint'ы**: приложение само разместит их в
> секции `endpoints`, а ссылки по `tag` в правилах и балансировщиках работают как обычно.

> **Ограничение v1:** Xray-форма стандартных outbound'ов (`vnext`, `servers`, `streamSettings`) **не поддерживается** —
> используйте sing-box-поля (`server`, `server_port`, `uuid`, `tls`, `transport`). Для offsite-протоколов это
> ограничение не действует: их `settings` — родной JSON приложения.

---

## 5. Маршрутизация (`routing.rules`)

Правила — Xray-совместимые, первое совпадение выигрывает.

```jsonc
"routing": {
  "rules": [
    { "domain": ["geosite:category-ads-all"], "outboundTag": "block" },
    { "domain": ["domain:example.org", "keyword:ads"], "outboundTag": "direct" },
    { "ip": ["geoip:ru", "10.0.0.0/8"], "outboundTag": "direct" },
    { "port": "80,443,1000-2000", "outboundTag": "wdtt-1" },
    { "source": ["192.168.0.0/16"], "outboundTag": "direct" },
    { "network": "udp", "balancerTag": "auto" },
    { "type": "field", "outboundTag": "sstp-1" }   // всё остальное
  ]
}
```

| Поле Xray | Смысл |
|---|---|
| `outboundTag` | тег outbound'а |
| `balancerTag` | тег балансировщика (см. §6) |
| `inboundTag` | тег входящего (обычно не нужен) |
| `domain` | домены; префиксы: `full:`, `domain:`, `keyword:`, `regexp:`, `geosite:`; без префикса = подстрока |
| `ip` | IP/CIDR; `geoip:` для стран |
| `port`, `sourcePort` | `"80,443"` или `"1000-2000"` |
| `source` | IP-адреса источника (CIDR) |
| `network` | `tcp`, `udp` или `tcp,udp` |
| `protocol` | протокол (`http`, `tls`, …) |
| `user` | имя пользователя inbound (best-effort) |
| `attrs` | не поддерживается (предупреждение) |

`geosite:`/`geoip:` берутся из каталога rule-set приложения (тот же, что у профилей роутинга). Если имя не найдено —
будет ошибка с указанием правила.

---

## 6. Балансировка (`routing.balancers`)

```jsonc
"balancers": [
  {
    "tag": "auto",
    "selector": ["wdtt-", "sstp-"],
    "strategy": { "type": "leastPing" },
    "fallbackTag": "direct"
  }
]
```

- `selector` — список **префиксов тегов**. В группу попадут все outbound'ы, чей тег начинается с любого префикса
  (например, `"wdtt-"` соберёт `wdtt-1`, `wdtt-2`, …).
- `strategy.type`:
  - `random` — распределение по соединениям (по умолчанию).
  - `leastPing` — выбирается узел с наименьшей задержкой (URL-тест).
  - `leastLoad` — здоровый узел с наименьшей задержкой/нагрузкой (приближение).
- `strategy.settings` (необязательно): `expected`, `maxRTT`, `tolerance`, `basetime`.
- `fallbackTag` — резервный outbound, добавляется последним участником группы.

Затем направляйте трафик на группу по её `tag`:

```jsonc
{ "type": "field", "network": "tcp,udp", "balancerTag": "auto" }
```

### Как это работает и ограничения

Внутри группы строятся **нативные группы ядра**:

| Xray | Реализация | Точность |
|---|---|---|
| `leastPing` | `urltest` | практически эквивалентно |
| `random` | `auto-selector` (баланс по соединениям) | близко (распределение может отличаться) |
| `leastLoad` | `auto-selector` | приближение: задержка + здоровье, **без метрики нагрузки** |
| нет `strategy` | как `random` | — |

`fallbackTag` не даёт «мягкого» переключения: он просто добавляется в группу, а `routing.final` по умолчанию —
первый outbound (как в Xray). Хотите явный выход по умолчанию — задайте `"final": "<tag>"`.

---

## 7. DNS

Необязательная секция. Поддерживаются `servers` и `queryStrategy`.

```jsonc
"dns": {
  "servers": [
    "1.1.1.1",
    { "address": "https://dns.google/dns-query", "tag": "google" },
    "tls://1.0.0.1"
  ],
  "queryStrategy": "UseIPv4"
}
```

| Адрес | Разбор |
|---|---|
| `1.1.1.1` / `udp://…` | UDP :53 |
| `tcp://…` | TCP :53 |
| `tls://…` | DNS-over-TLS :853 |
| `https://host/path` | DNS-over-HTTPS |
| `localhost` | локальный resolver |

`queryStrategy`: `UseIP` → prefer IPv4, `UseIPv4` → только IPv4, `UseIPv6` → только IPv6, `AsIs` → без указания.

Значения `log.loglevel` (`info`/`warning`/`error`/`none`) принимаются как в Xray и переводятся в sing-box (`level`/`disabled`); остальные поля Xray-лога игнорируются.

Неподдерживаемые схемы DNS (`quic://`, `h3://` и т.п.) игнорируются с предупреждением; если серверов не осталось, используется DNS приложения.

Поля `dns.rules` и `dns.hosts`, а также per-server `domains`/`expectIPs` в v1 **игнорируются** (предупреждение).

---

## 8. Ограничения и правила (важно)

- Профиль **самодостаточен**: встроенные профили роутинга приложения для него **не применяются**.
- Профиль **не участвует в цепочках** как звено (аналогично «Custom config»).
- `inbounds` игнорируются; tun/socks добавляет приложение.
- Предупреждения (не блокируют): неизвестная секция верхнего уровня, `streamSettings` у offsite, `attrs`,
  `dns.rules`/`dns.hosts`.
- Лимиты: документ ≤ 1 МиБ, ≤ 256 outbound'ов, ≤ 2048 правил.
- Ошибки (блокируют запуск): не JSON-объект, нет `outbounds`, дубли `tag`, неизвестный протокол, некорректные
  `settings`, ссылки на несуществующие теги, `selector` без совпадений, неизвестная стратегия.

---

## 9. Диагностика

| Сообщение | Причина / что делать |
|---|---|
| `document is not a JSON object` | текст не JSON-объект |
| `outbounds: required section is missing` | нет секции `outbounds` |
| `outbounds: must contain at least one outbound` | пустой `outbounds` |
| `outbounds[N].tag: duplicate tag` | теги должны быть уникальны |
| `outbounds[N].protocol: unknown protocol: X` | протокол вне списка поддерживаемых |
| `outbounds[N].settings: invalid settings for protocol X` | не хватает/неверны поля протокола |
| `standard outbound T (protocol P) must use sing-box-shaped settings…` | стандартный outbound задан в Xray-форме — переделайте в sing-box-поля |
| `routing.balancers[N].selector: selector does not match any outbound tag` | префил `selector` никого не нашёл |
| `outboundTag: unknown outbound tag: X` | правило ссылается на несуществующий тег |
| `routing.final: unknown tag: X` | `final` указывает на несуществующий тег |
| `unknown strategy: X` | стратегия не из `random`/`leastPing`/`leastLoad` |
| `unknown fallback tag: X` / `balancer cannot fall back to itself` | `fallbackTag` указывает на несуществующий тег или на себя |
| `duplicate DNS server tag: X` | два DNS-сервера с одинаковым `tag` |
| `invalid port range: X` | порт/диапазон вне `1..65535` |
| `not an IP or CIDR: X` | в `ip`/`source` не CIDR/IP (напр. `ext:...`) |
| `unsupported DNS address; ignored` | схема DNS не поддерживается (`quic://`, `h3://`, …) |
| `Unknown rule-set "x" in routing.rules[N]…` | `geosite:x`/`geoip:x` не найден в каталоге |
| `Некорректная конфигурация SSTP…` (и аналоги) | поля протокола заполнены неверно |
| `Transport client is not installed` | в APK нет нативных клиентов: перед сборкой выполните `python3 scripts/stage_native_transports.py` и пересоберите APK |

Порядок разбора: **Check config** → исправить ошибки → сохранить → подключиться. Логи клиентов доступны в
приложении (вкладка логов).

---

## 10. Полный пример

Два qWDTT + SSTP, гео-маршрутизация и балансировка:

```jsonc
{
  "remarks": "qColdThrone demo",
  "log": { "loglevel": "warning" },
  "dns": {
    "servers": ["1.1.1.1", "tls://1.0.0.1"],
    "queryStrategy": "UseIPv4"
  },
  "outbounds": [
    {
      "tag": "wdtt-1",
      "protocol": "qwdtt",
      "settings": {
        "server": "turn1.example.com",
        "server_port": 56000,
        "password": "секрет",
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
        "password": "секрет",
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
        "password": "pass"
      }
    },
    { "tag": "direct", "protocol": "freedom" },
    { "tag": "block", "protocol": "blackhole" }
  ],
  "routing": {
    "rules": [
      { "domain": ["geosite:category-ads-all"], "outboundTag": "block" },
      { "ip": ["geoip:ru"], "outboundTag": "direct" },
      { "network": "tcp,udp", "balancerTag": "auto" }
    ],
    "balancers": [
      {
        "tag": "auto",
        "selector": ["wdtt-"],
        "strategy": { "type": "leastPing" },
        "fallbackTag": "sstp-1"
      }
    ],
    "final": "auto"
  }
}
```

---

## 11. FAQ

**Как добавить третий узел qWDTT?** Дублируйте объект в `outbounds`, смените `tag` и `settings` — он автоматически
попадёт в группу `auto`, если тег начинается с `wdtt-`.

**Обязательно ли указывать `type` в `settings`?** Нет, приложение подставит его из `protocol`.

**Где взять `settings` для протокола?** Создайте обычный профиль и скопируйте его через «Raw JSON».

**Можно ли смешивать offsite и обычные прокси?** Да — обычные задаются в sing-box-форме (§4).

**Как считается пинг профиля?** Через реальное ядро измеряется задержка основного egress документа —
`routing.final`, иначе первый outbound или группа (тот выход, куда реально уходит трафик). Правила маршрутизации
в тест-бокс не попадают: они не влияют на dial. Для конфига с главным узлом-эндпоинтом (AmneziaWG/WireGuard)
измеряется он.

**Почему игнорируется `inbounds`?** Приложение само управляет входом (tun/socks).

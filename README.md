# Паралельна пошукова система з LLM

Курсова робота з паралельних обчислень.

## Опис

Контейнеризована пошукова система на Java 21. Вона будує інвертований індекс над текстовим
датасетом паралельно (власний thread pool і власна thread-safe структура даних), обробляє
запити від кількох клієнтів одночасно та розширює запит за допомогою локальної LLM (Ollama).

Можливості:

- паралельна побудова інвертованого індексу;
- пошук за ключовими словами;
- розширення запиту (query expansion) через Ollama; якщо Ollama недоступна, пошук іде по
  оригінальному запиту;
- REST API та WebSocket `/ws/search`;
- клієнти: консольний (CLI) і веб-інтерфейс (Web UI);
- запуск однією командою `docker compose up`.

> Статус: каркас проєкту (етап 1). Функціональність додається поетапно, див. `CONTEXT.md`.

## Вимоги

- Docker та Docker Compose (для запуску системи);
- JDK 21 та Maven 3.9+ (лише для локальної розробки без Docker);
- Python 3.10+ з `matplotlib` (лише для бенчмарків у `bench/`);
- ~2 ГБ вільного місця для розпакованого датасету.

## Запуск

Буде доповнено на етапі 2 (Docker). Планована команда:

```bash
docker compose up
```

Локальна збірка:

```bash
mvn compile            # компіляція всіх модулів
mvn test               # тести
mvn spotless:apply     # автоформатування (Google Java Style)
```

## API

| Метод | Шлях | Тіло запиту | Відповідь |
|-------|------|-------------|-----------|
| GET | `/api/health` | - | `{ "status": "ok", "ollama": true }` |
| POST | `/api/index` | `{ "threads": 4 }` | `{ "status": "ok", "documents_indexed": N, "time_ms": T }` |
| POST | `/api/search` | `{ "query": "text", "mode": "keyword" }` | `{ "results": [{"doc_id": "...", "score": 0.95, "snippet": "..."}], "time_ms": T }` |
| GET | `/api/stats` | - | `{ "documents": N, "index_build_time_ms": T, "threads_used": K }` |

WebSocket `/ws/search`: клієнт надсилає `{ "query": "..." }`, сервер відповідає по одному
JSON-повідомленню на етап (`query_expansion`, `keyword_search`, `done`).

## Датасет

Джерело: [Text files from Gutenberg database](https://zenodo.org/records/3360392)
(Antonis Michalas, Tampere University, CC BY 4.0).

1. Завантажте архів `D1.7GB.zip` (~563 МБ) зі сторінки Zenodo.
2. Розпакуйте його в каталог `data/` у корені проєкту.
3. Каталог `data/` додано в `.gitignore`, датасет у git не потрапляє.

Менші архіви (184 МБ, 357 МБ, 670 МБ, 1 ГБ) можна використовувати для швидкого дебагу та
для графіка залежності часу від розміру даних.

## Тести

```bash
mvn test
```

## Структура проєкту

```
.
├── server/                  # сервер (Javalin)
│   └── src/main/java/ua/coursework/engine/
│       ├── api/             # REST і WebSocket маршрути
│       ├── index/           # інвертований індекс, токенізатор, індексатор
│       ├── concurrent/      # власний ThreadPool
│       ├── search/          # SearchService
│       └── llm/             # клієнт Ollama, розширення запиту
├── server/src/main/resources/web/   # Web UI
├── cli/                     # консольний клієнт
├── bench/                   # бенчмарки та графіки (Python)
├── docs/uml/                # UML-діаграми
├── docs/report/             # розділи звіту
├── CONTEXT.md               # контекст проєкту для нових чатів
└── COMMITS.md               # правила commit-повідомлень
```

## Git

Правила commit-повідомлень: [COMMITS.md](COMMITS.md).

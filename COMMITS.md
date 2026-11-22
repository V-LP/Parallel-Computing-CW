# Правила commit-повідомлень

Формат Conventional Commits:

```
<type>(<scope>): <короткий опис>
```

- `scope` необов'язковий: `server`, `cli`, `index`, `pool`, `llm`, `api`, `web`, `docker`, `bench`.
- Опис англійською, у наказовому способі, без крапки в кінці, до ~70 символів.
- Один коміт = одна логічна зміна (атомарний коміт); код і тест до нього краще разом.

## Типи

| Тип | Коли використовувати | Приклад |
|-----|----------------------|---------|
| `feat` | нова функціональність | `feat(index): add striped-lock inverted index` |
| `fix` | виправлення помилки | `fix(pool): stop workers on shutdown` |
| `docs` | README, звіт, UML, CONTEXT.md | `docs: describe dataset setup in README` |
| `test` | додавання або зміна тестів | `test(index): cover concurrent writes` |
| `chore` | службові зміни без впливу на код (`.gitignore`, структура) | `chore: add .gitignore` |
| `refactor` | зміна коду без зміни поведінки | `refactor(search): extract ranking` |
| `build` | Maven, Dockerfile, docker-compose | `build: add Javalin and Jackson` |

Зламані контракти позначайте `!`: `feat(api)!: rename field` (контракт API фіксований, тож це
малоймовірно).

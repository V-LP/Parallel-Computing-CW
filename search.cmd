@echo off
setlocal
set "ROOT=%~dp0"
set "JAR=%ROOT%cli\target\search-cli.jar"

rem utf-8 code page for the run
set "OLDCP="
for /f "tokens=2 delims=:." %%a in ('chcp') do set "OLDCP=%%a"
chcp 65001 >nul
set "CODE=0"

where java >nul 2>nul
if errorlevel 1 (
  echo Java 21 не знайдено. Встановіть JDK 21 або запустіть через Docker: docker compose run --rm cli "запит" 1>&2
  set "CODE=1"
  goto :done
)

if not exist "%JAR%" (
  where mvn >nul 2>nul
  if errorlevel 1 (
    echo Файл cli\target\search-cli.jar відсутній, а Maven не знайдено. 1>&2
    echo Встановіть Maven 3.9+ і JDK 21 та виконайте: mvn -q -pl cli -am package -DskipTests 1>&2
    echo або запустіть через Docker: docker compose run --rm cli "запит" 1>&2
    set "CODE=1"
    goto :done
  )
  echo Збираю CLI ^(один раз^)... 1>&2
  call mvn -f "%ROOT%pom.xml" -q -pl cli -am package -DskipTests 1>&2
  if errorlevel 1 (
    echo Не вдалося зібрати CLI. Подивіться повідомлення Maven вище. 1>&2
    set "CODE=1"
    goto :done
  )
)

java -jar "%JAR%" %*
set "CODE=%ERRORLEVEL%"

:done
if defined OLDCP chcp %OLDCP% >nul
exit /b %CODE%

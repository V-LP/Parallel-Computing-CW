# Runs the console client: .\search.ps1 "white whale"   (see README, section "CLI").
# Builds cli\target\search-cli.jar on the first run if it is missing.
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$jar = Join-Path $root 'cli\target\search-cli.jar'

if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    [Console]::Error.WriteLine('Java 21 не знайдено. Встановіть JDK 21 або запустіть через Docker: docker compose run --rm cli "запит"')
    exit 1
}

if (-not (Test-Path $jar)) {
    if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
        [Console]::Error.WriteLine('Файл cli\target\search-cli.jar відсутній, а Maven не знайдено.')
        [Console]::Error.WriteLine('Встановіть Maven 3.9+ і JDK 21 та виконайте: mvn -q -pl cli -am package -DskipTests')
        [Console]::Error.WriteLine('або запустіть через Docker: docker compose run --rm cli "запит"')
        exit 1
    }
    [Console]::Error.WriteLine('Збираю CLI (один раз)...')
    Push-Location $root
    try {
        & mvn -q -pl cli -am package -DskipTests
        $buildCode = $LASTEXITCODE
    } finally {
        Pop-Location
    }
    if ($buildCode -ne 0) {
        [Console]::Error.WriteLine('Не вдалося зібрати CLI. Подивіться повідомлення Maven вище.')
        exit 1
    }
}

# The client prints UTF-8: switch the console encoding for the run, then restore it.
$oldOutput = [Console]::OutputEncoding
$oldPipe = $OutputEncoding
$utf8 = [System.Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = $utf8
$OutputEncoding = $utf8   # encoding of text piped to the client (query "-" from stdin)
try {
    if ($MyInvocation.ExpectingInput) {
        $input | & java -jar $jar @args
    } else {
        & java -jar $jar @args
    }
    $code = $LASTEXITCODE
} finally {
    [Console]::OutputEncoding = $oldOutput
    $OutputEncoding = $oldPipe
}
exit $code

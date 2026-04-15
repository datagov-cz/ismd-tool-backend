# Loads .env and starts the full-backend profile (includes Spring Boot backend)
Get-Content .env | ForEach-Object {
    if ($_ -match '^\s*([^#][^=]+)=(.*)$') {
        [System.Environment]::SetEnvironmentVariable($matches[1].Trim(), $matches[2].Trim())
    }
}

docker-compose --profile full-backend up -d @args

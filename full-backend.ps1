# Usage:
#   .\full-backend.ps1 up   [docker compose up options]    (e.g. --build, --wait, --remove-orphans)
#   .\full-backend.ps1 down [docker compose down options]  (e.g. -v, --remove-orphans)
#
# A param() block + ValueFromRemainingArguments is required because PowerShell
# otherwise eats short switches like -v (binds to the common -Verbose parameter)
# before $args ever sees them.
[CmdletBinding(PositionalBinding = $false)]
param(
    [Parameter(Position = 0)]
    [ValidateSet('up', 'down')]
    [string]$Command = 'up',

    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Rest
)

Get-Content .env | ForEach-Object {
    if ($_ -match '^\s*([^#][^=]+)=(.*)$') {
        [System.Environment]::SetEnvironmentVariable($matches[1].Trim(), $matches[2].Trim())
    }
}

$extra = if ($Rest) { $Rest } else { @() }

switch ($Command) {
    'up'   { docker compose --profile full-backend up -d @extra }
    'down' { docker compose --profile full-backend down @extra }
}

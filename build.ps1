# Cinematic Travel Camera (forgeward_travelcam) -- hand build (javac + jar, no Gradle).
# Rebuild:  powershell -ExecutionPolicy Bypass -File build.ps1
# Override paths with env vars JDK / SERVER / CLIENTLIBS.
#
# This mod is CLIENT-ONLY and compiles against the NAMED client jars. clientlibs (git-ignored) = client-1.21.1-*-srg.jar +
# neoforge-21.1.250-client.jar, as a NeoForge 21.1.250 installer run in client mode leaves them. SERVER is a NeoForge 21.1.250
# server dir, for its libraries/.
$ErrorActionPreference = "Stop"

if ($env:JDK) { $JDK = $env:JDK } elseif ($env:JAVA_HOME) { $JDK = $env:JAVA_HOME } else { $JDK = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot" }
if ($env:SERVER) { $SERVER = $env:SERVER } else { $SERVER = Join-Path $PSScriptRoot "..\..\server" }
$PROJ = $PSScriptRoot
if ($env:CLIENTLIBS) { $CLIB = $env:CLIENTLIBS } else { $CLIB = Join-Path $PROJ "clientlibs" }
$NFV  = "21.1.250"
$MCV  = "1.21.1-20240808.144430"
$LIB  = Join-Path $SERVER "libraries"
$NF   = Join-Path $LIB "net\neoforged\neoforge\$NFV"
$CLIENT_SRG = Join-Path $CLIB "client-$MCV-srg.jar"
$CLIENT_NF  = Join-Path $CLIB "neoforge-$NFV-client.jar"
if (-not ((Test-Path $CLIENT_SRG) -and (Test-Path $CLIENT_NF))) { throw "client jars missing in $CLIB" }

$BUILD  = Join-Path $PROJ "build"
$OUT    = Join-Path $BUILD "classes"
$JAROUT = Join-Path $PROJ "forgeward_travelcam-1.1.4.jar"
if (Test-Path $BUILD) { Remove-Item -Recurse -Force $BUILD }
New-Item -ItemType Directory -Force -Path $OUT | Out-Null

function Need($subdir, $pattern) {
    $hit = Get-ChildItem (Join-Path $LIB $subdir) -Recurse -Filter $pattern -ErrorAction SilentlyContinue |
           Sort-Object Name -Descending | Select-Object -First 1
    if (-not $hit) { throw "$pattern not found under $LIB\$subdir" }
    return $hit.FullName
}

# Sable and Create are OPTIONAL at runtime (SableFrame / ContraptionFrame load only when the mod is present);
# compile only. Sable's API extends its jar-in-jar companion, pulled out of its jar.
$MODS = Join-Path $SERVER "mods"
Add-Type -AssemblyName System.IO.Compression.FileSystem
$COMP = Join-Path $PROJ "clientlibs\sable-companion-common-1.21.1-1.6.0.jar"
if (-not (Test-Path $COMP)) { New-Item -ItemType Directory -Force (Split-Path $COMP) | Out-Null; $sj = [System.IO.Compression.ZipFile]::OpenRead((Join-Path $MODS "sable-neoforge-1.21.1-2.0.5.jar")); try { [System.IO.Compression.ZipFileExtensions]::ExtractToFile($sj.GetEntry("META-INF/jarjar/sable-companion-common-1.21.1-1.6.0.jar"), $COMP, $true) } finally { $sj.Dispose() } }
$ROUTES = (Get-ChildItem $MODS -Filter "forgeward_routes-*.jar" | Sort-Object Name -Descending | Select-Object -First 1).FullName   # HorsePlan, compile-only, read behind ModList.isLoaded
$OPTIONAL = @($COMP, $ROUTES, (Join-Path $MODS "sable-neoforge-1.21.1-2.0.5.jar"), (Join-Path $MODS "create-1.21.1-6.0.10.jar"))
$CP = @(
    $CLIENT_NF, $CLIENT_SRG,
    (Join-Path $NF "neoforge-$NFV-universal.jar"),
    (Need "net\neoforged\fancymodloader\loader" "loader-*.jar"),
    (Need "net\neoforged\bus" "bus-*.jar"),
    (Need "net\neoforged\mergetool" "mergetool-*-api.jar"),
    (Need "org\ow2\asm\asm-tree" "asm-tree-*.jar"), (Need "org\ow2\asm\asm" "asm-9*.jar"),
    (Need "com\mojang\logging" "logging-*.jar"),
    (Need "org\slf4j\slf4j-api" "slf4j-api-*.jar"),
    (Need "it\unimi\dsi\fastutil" "fastutil-*.jar"),
    (Need "com\mojang\datafixerupper" "datafixerupper-*.jar"),
    (Need "com\mojang\brigadier" "brigadier-*.jar"),
    (Need "com\mojang\authlib" "authlib-*.jar"),
    (Need "org\joml" "joml-*.jar"),
    (Need "com\google\code\gson\gson\2.10.1" "gson-*.jar"),
    (Need "io\netty\netty-buffer" "netty-buffer-*.jar"),
    (Need "io\netty\netty-common" "netty-common-*.jar"),
    (Join-Path $LIB "net\fabricmc\sponge-mixin\0.15.2+mixin.0.8.7\sponge-mixin-0.15.2+mixin.0.8.7.jar")
) + $OPTIONAL
$CP = $CP -join ";"

Write-Host "[1/2] javac (Java 21) ..."
$SRC = @(Get-ChildItem -Recurse -Filter *.java (Join-Path $PROJ "src\main\java") | ForEach-Object { $_.FullName })
& (Join-Path $JDK "bin\javac.exe") -proc:none -encoding UTF-8 --release 21 -classpath $CP -d $OUT $SRC
if ($LASTEXITCODE -ne 0) { throw "javac failed ($LASTEXITCODE)" }

Write-Host "[2/2] jar ..."
$RES = Join-Path $PROJ "src\main\resources"
if (Test-Path $JAROUT) { Remove-Item -Force $JAROUT }
& (Join-Path $JDK "bin\jar.exe") --create --file $JAROUT -C $OUT . -C $RES .
if ($LASTEXITCODE -ne 0) { throw "jar failed ($LASTEXITCODE)" }
Write-Host "Built: $JAROUT"

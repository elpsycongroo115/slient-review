param(
    [string]$IdeaHome = $env:IDEA_HOME,
    [string]$JavaHome,
    [string]$PlatformPath,
    [switch]$Compatibility2024,
    [string[]]$Tasks = @('test', 'buildPlugin')
)
$ErrorActionPreference = 'Stop'
if (-not $IdeaHome -and -not $JavaHome) { throw '请通过 -IdeaHome 指定 IDEA 安装目录，或通过 -JavaHome 指定 JDK 21。' }
$resolvedIdea = if ($IdeaHome) { (Resolve-Path -LiteralPath $IdeaHome).Path } else { $null }
$runtimePath = if ($JavaHome) { (Resolve-Path -LiteralPath $JavaHome).Path } else { Join-Path $resolvedIdea 'jbr' }
if (-not (Test-Path -LiteralPath (Join-Path $runtimePath 'bin\javac.exe'))) {
    throw '所选 IDEA 的 jbr 中没有可用的 javac；需要带编译器的 Java 21 运行时。'
}
$releaseText = Get-Content -LiteralPath (Join-Path $runtimePath 'release') -Raw
if ($releaseText -notmatch 'JAVA_VERSION="21[."]') { throw '构建工具链需要 JDK 21。较早的 IDEA 2024.1 自带 Java 17，请额外通过 -JavaHome 指定 JDK 21；直接安装兼容包不需要构建。' }
$workspacePath = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$cachedGradle = Join-Path $workspacePath '.tools\gradle-9.8.2\bin\gradle.bat'
$gradleCommand = if (Test-Path -LiteralPath $cachedGradle) { $cachedGradle } else { Join-Path $workspacePath 'gradlew.bat' }
$previousJavaHome = $env:JAVA_HOME
Push-Location -LiteralPath $workspacePath
try {
    $env:JAVA_HOME = $runtimePath
    $gradleArguments = @($Tasks)
    # Every distribution now targets 2024.1 / Java 17. IdeaHome supplies the
    # build JDK; a newer installed IDE must not raise the compilation baseline.
    if ($PlatformPath) {
        $resolvedPlatform = (Resolve-Path -LiteralPath $PlatformPath).Path
        $gradleArguments += "-PlocalPlatformPath=$resolvedPlatform"
    } elseif ($resolvedIdea) {
        $productInfo = Get-Content -LiteralPath (Join-Path $resolvedIdea 'product-info.json') -Raw
        if ($productInfo -match '"buildNumber"\s*:\s*"241\.') {
            $gradleArguments += "-PlocalPlatformPath=$resolvedIdea"
        }
    }
    $gradleArguments += "-Porg.gradle.java.installations.paths=$runtimePath"
    & $gradleCommand @gradleArguments '--console=plain'
    if ($LASTEXITCODE -ne 0) { throw "Gradle 执行失败，退出码 $LASTEXITCODE" }
} finally {
    $env:JAVA_HOME = $previousJavaHome
    Pop-Location
}

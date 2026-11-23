param(
    [string] $Destination = "target/avia-distribution",
    [string] $RuntimePriceModel
)

$ErrorActionPreference = "Stop"
$repository = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
if (![IO.Path]::IsPathRooted($Destination)) {
    $Destination = Join-Path $repository $Destination
}
$output = [IO.Path]::GetFullPath($Destination)
if (Test-Path -LiteralPath $output) {
    throw "Destination already exists: $output"
}

$files = [ordered]@{
    "compose.yaml" = "compose.yaml"
    ".env.example" = ".env.example"
    ".dockerignore" = ".dockerignore"
    "deploy/RUN.md" = "README.md"
    "deploy/Dockerfile.delay" = "deploy/Dockerfile.delay"
    "deploy/restore-archive.sh" = "deploy/restore-archive.sh"
    "avia_archive" = "avia_archive"
    "target/avia-backend.jar" = "target/avia-backend.jar"
    "src/main/resources/models/price_model.onnx" = "src/main/resources/models/price_model.onnx"
    "models/delay_model_v2.cbm" = "models/delay_model_v2.cbm"
    "models/delay_model_v2_metadata.json" = "models/delay_model_v2_metadata.json"
}
foreach ($module in @("__init__", "config", "features", "historical_features", "predictor", "preprocessing", "serve_delay")) {
    $files["ml/$module.py"] = "ml/$module.py"
}
if ($RuntimePriceModel) {
    $files["src/main/resources/models/price_model.onnx"] = "models/original/price_model.onnx"
    $files[$RuntimePriceModel] = "src/main/resources/models/price_model.onnx"
}
$inputs = foreach ($name in $files.Keys) {
    $source = if ([IO.Path]::IsPathRooted($name)) { $name } else { Join-Path $repository $name }
    if (!(Test-Path -LiteralPath $source -PathType Leaf) -or (Get-Item -LiteralPath $source).Length -eq 0) {
        throw "Required file is missing or empty: $source"
    }
    @{ Source = $source; Target = Join-Path $output $files[$name] }
}
foreach ($file in $inputs) {
    [IO.Directory]::CreateDirectory((Split-Path -Parent $file.Target)) | Out-Null
    Copy-Item -LiteralPath $file.Source -Destination $file.Target
}
Write-Output $output

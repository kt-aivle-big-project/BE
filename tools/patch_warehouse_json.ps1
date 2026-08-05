# ============================================================
# Rewrite a warehouse map JSON so the Spring importer builds the
# same facility contract as the seeded demo warehouse
# (Daejeon Logistics Center A).
#
#   inbound_access   -> inbound_handoff_access   (N facilities x 2 sides)
#   outbound_access  -> outbound_station_access  (N facilities x 1 side)
#   empty_tote_*     -> gets a buffer_id
#
# Node ids and coordinates are untouched, so every edge still
# resolves and nothing has to be edited by hand.
#
# Usage - just run it, a file picker opens:
#   powershell -ExecutionPolicy Bypass -File .\tools\patch_warehouse_json.ps1
#
# The result is written next to the source as *_patched.json
#
# ASCII only on purpose: Windows PowerShell 5.1 reads .ps1 using the
# system code page, so non-ASCII characters break parsing.
# ============================================================

param(
    [string]$InputPath,
    [string]$OutputPath
)

$ErrorActionPreference = "Stop"

# ------------------------------------------------------------
# Ask for the file when no path was given.
# ------------------------------------------------------------
if (-not $InputPath) {
    Add-Type -AssemblyName System.Windows.Forms | Out-Null

    $dialog = New-Object System.Windows.Forms.OpenFileDialog
    $dialog.Title = "Pick the warehouse map JSON"
    $dialog.Filter = "JSON files (*.json)|*.json|All files (*.*)|*.*"
    $dialog.InitialDirectory = [Environment]::GetFolderPath("UserProfile")

    if ($dialog.ShowDialog() -ne [System.Windows.Forms.DialogResult]::OK) {
        Write-Host "Cancelled." -ForegroundColor Yellow
        return
    }
    $InputPath = $dialog.FileName
}

if (-not (Test-Path -LiteralPath $InputPath)) {
    throw "File not found: $InputPath"
}

if (-not $OutputPath) {
    $directory = Split-Path -Parent $InputPath
    $baseName  = [System.IO.Path]::GetFileNameWithoutExtension($InputPath)
    $OutputPath = Join-Path $directory "$baseName`_patched.json"
}

Write-Host ""
Write-Host "Source: $InputPath" -ForegroundColor Cyan

$raw = Get-Content -LiteralPath $InputPath -Raw -Encoding UTF8
$map = $raw | ConvertFrom-Json

$nodes = @($map.nodes)
$edges = @($map.edges)

# ------------------------------------------------------------
# Find the aisle node this access spot is wired to.
# Read the real edges instead of guessing from the id, because
# aisle naming differs between maps.
# ------------------------------------------------------------
$nodeTypeById = @{}
foreach ($node in $nodes) { $nodeTypeById[$node.id] = $node.type }

function Get-AdjacentRouteNode {
    param([string]$NodeId)

    foreach ($edge in $edges) {
        $other = $null
        if     ($edge.source -eq $NodeId) { $other = $edge.target }
        elseif ($edge.target -eq $NodeId) { $other = $edge.source }
        if (-not $other) { continue }

        $otherType = $nodeTypeById[$other]
        if ($otherType -eq "route" -or $otherType -eq "route_charge_junction") {
            return $other
        }
    }
    return $null
}

# ------------------------------------------------------------
# Pick the logical ports this access spot serves, nearest by y.
# These ids are display only; routing does not depend on them.
# ------------------------------------------------------------
function Get-NearestPortIds {
    param($AccessNode, $Ports, [int]$Count = 3)

    if ($Ports.Count -eq 0) { return @() }

    $take = [Math]::Min($Count, $Ports.Count)
    return @(
        $Ports |
            Sort-Object { [Math]::Abs([double]$_.y - [double]$AccessNode.y) } |
            Select-Object -First $take |
            Sort-Object { [double]$_.y } |
            ForEach-Object { $_.id }
    )
}

function Add-Field {
    param($Node, [string]$Name, $Value)

    if ($Node.PSObject.Properties.Name -contains $Name) {
        $Node.$Name = $Value
    } else {
        $Node | Add-Member -NotePropertyName $Name -NotePropertyValue $Value
    }
}

$inboundPorts  = @($nodes | Where-Object { $_.type -eq "inbound"  } | Sort-Object { [double]$_.y })
$outboundPorts = @($nodes | Where-Object { $_.type -eq "outbound" } | Sort-Object { [double]$_.y })

# ------------------------------------------------------------
# 1. Inbound access spots -> one facility per two spots (A / B)
# ------------------------------------------------------------
$inboundAccess = @($nodes | Where-Object { $_.type -eq "inbound_access" } | Sort-Object { [double]$_.y })

for ($index = 0; $index -lt $inboundAccess.Count; $index++) {
    $node = $inboundAccess[$index]
    $facilityNumber = [Math]::Floor($index / 2) + 1
    $side = if ($index % 2 -eq 0) { "A" } else { "B" }

    Add-Field $node "type"             "inbound_handoff_access"
    Add-Field $node "handoff_id"       "IN_HANDOFF_$facilityNumber"
    Add-Field $node "side"             $side
    Add-Field $node "display_port_ids" (Get-NearestPortIds $node $inboundPorts 3)
    Add-Field $node "service_only"     $true
    Add-Field $node "transit_allowed"  $false
    Add-Field $node "holding_allowed"  $false
    Add-Field $node "node_capacity"    1

    $adjacent = Get-AdjacentRouteNode $node.id
    if ($adjacent) { Add-Field $node "adjacent_route_node" $adjacent }
}

# ------------------------------------------------------------
# 2. Outbound access spots -> one facility each
# ------------------------------------------------------------
$outboundAccess = @($nodes | Where-Object { $_.type -eq "outbound_access" } | Sort-Object { [double]$_.y })

for ($index = 0; $index -lt $outboundAccess.Count; $index++) {
    $node = $outboundAccess[$index]
    $facilityNumber = $index + 1

    Add-Field $node "type"              "outbound_station_access"
    Add-Field $node "station_id"        "OUT_STATION_$facilityNumber"
    Add-Field $node "side"              "A"
    Add-Field $node "display_chute_ids" (Get-NearestPortIds $node $outboundPorts 3)
    Add-Field $node "service_only"      $true
    Add-Field $node "transit_allowed"   $false
    Add-Field $node "holding_allowed"   $false
    Add-Field $node "node_capacity"     1

    $adjacent = Get-AdjacentRouteNode $node.id
    if ($adjacent) { Add-Field $node "adjacent_route_node" $adjacent }
}

# ------------------------------------------------------------
# 3. Empty tote buffer -> buffer_id
#    Without it the AI refuses the map:
#    Empty-tote access node ETB_0 is missing buffer_id.
# ------------------------------------------------------------
$bufferAccess = @($nodes | Where-Object { $_.type -eq "empty_tote_buffer_access" })

for ($index = 0; $index -lt $bufferAccess.Count; $index++) {
    $node = $bufferAccess[$index]
    Add-Field $node "buffer_id" "EMPTY_TOTE_BUFFER_$($index + 1)"

    if (-not $node.adjacent_route_node) {
        $adjacent = Get-AdjacentRouteNode $node.id
        if ($adjacent) { Add-Field $node "adjacent_route_node" $adjacent }
    }
}

# ------------------------------------------------------------
# Save
# ------------------------------------------------------------
$json = $map | ConvertTo-Json -Depth 30
[System.IO.File]::WriteAllText($OutputPath, $json, (New-Object System.Text.UTF8Encoding($false)))

$handoffFacilities = [Math]::Ceiling($inboundAccess.Count / 2)

Write-Host ""
Write-Host "Done: $OutputPath" -ForegroundColor Green
Write-Host "  inbound access  $($inboundAccess.Count)  -> $handoffFacilities handoff facilities"
Write-Host "  outbound access $($outboundAccess.Count)  -> $($outboundAccess.Count) station facilities"
Write-Host "  empty tote      $($bufferAccess.Count)"
Write-Host ""

if ($inboundAccess.Count -eq 0 -and $outboundAccess.Count -eq 0 -and $bufferAccess.Count -eq 0) {
    Write-Host "Nothing to change. This map already uses the facility contract." -ForegroundColor Yellow
} else {
    Write-Host "Node ids and coordinates unchanged, so edges were not touched." -ForegroundColor DarkGray
    Write-Host "Upload the *_patched.json file when creating the warehouse." -ForegroundColor DarkGray
}

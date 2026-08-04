param(
    [int64]$WarehouseId = 1,
    [string]$ComposeDirectory = (Join-Path $PSScriptRoot "..\..\AI"),
    [string]$OutputPath = (Join-Path $PSScriptRoot "..\src\main\resources\db\seed\V09_current_warehouse_state.sql")
)

$ErrorActionPreference = "Stop"
$nullMarker = "__LARO_SQL_NULL__"

function Invoke-PostgresCsv {
    param([Parameter(Mandatory = $true)][string]$Query)

    Push-Location -LiteralPath $ComposeDirectory
    try {
        $lines = @(& docker compose --env-file .env.docker exec -T postgres `
            psql -U postgres -d warehouse --csv --pset "null=$nullMarker" -c $Query)
        if ($LASTEXITCODE -ne 0) {
            throw "PostgreSQL snapshot query failed with exit code $LASTEXITCODE."
        }
    }
    finally {
        Pop-Location
    }

    if ($lines.Count -eq 0) {
        return @()
    }
    return @(($lines -join "`n") | ConvertFrom-Csv)
}

function SqlText([object]$Value) {
    if ($null -eq $Value -or [string]$Value -eq $nullMarker) {
        return "NULL"
    }
    return "'" + ([string]$Value).Replace("'", "''") + "'"
}

function SqlJson([object]$Value) {
    $literal = SqlText $Value
    if ($literal -eq "NULL") {
        return $literal
    }
    return "$literal::jsonb"
}

function SqlNumber([object]$Value) {
    if ($null -eq $Value -or [string]$Value -eq $nullMarker) {
        return "NULL"
    }
    return [string]$Value
}

function SqlBoolean([object]$Value) {
    if ($null -eq $Value -or [string]$Value -eq $nullMarker) {
        return "NULL"
    }
    if ([string]$Value -in @("t", "true", "True", "1")) {
        return "TRUE"
    }
    return "FALSE"
}

$nodeRows = Invoke-PostgresCsv @"
SELECT node_id, warehouse_id, zone_id, node_code, node_type, x, y,
       is_active, service_only, transit_allowed, holding_allowed,
       node_capacity, resource_type, resource_code, side,
       route_attributes::text AS route_attributes
FROM public.warehouse_node
WHERE warehouse_id = $WarehouseId
ORDER BY node_id
"@

$edgeRows = Invoke-PostgresCsv @"
SELECT edge_id, edge_code, from_node_id, to_node_id, distance,
       direction_type, edge_type, speed_limit_mps,
       nominal_travel_time_ms, cost, physical_resource_code,
       service_only, mobile_robot_traversable,
       route_attributes::text AS route_attributes
FROM public.warehouse_edge
WHERE from_node_id IN (
    SELECT node_id FROM public.warehouse_node WHERE warehouse_id = $WarehouseId
)
ORDER BY edge_id
"@

if ($nodeRows.Count -eq 0 -or $edgeRows.Count -eq 0) {
    throw "Warehouse $WarehouseId has no map snapshot to export."
}

$activeNodeCount = @(
    $nodeRows | Where-Object { [string]$_.is_active -in @("t", "true", "True", "1") }
).Count

$sql = [System.Collections.Generic.List[string]]::new()
$sql.Add("-- V09: current warehouse 1 map snapshot")
$sql.Add("-- Exported from the BE-centered PostgreSQL map by tools/export_current_warehouse_v09.ps1.")
$sql.Add("-- This seed preserves runtime inventory/tasks while replacing the active route-map contract.")
$sql.Add("")
$sql.Add("-- LARO does not use expiry-based product or inventory policies.")
$sql.Add("DROP VIEW IF EXISTS laro_ext.be_inventory_unit_v;")
$sql.Add("ALTER TABLE public.warehouse_items DROP COLUMN IF EXISTS expiry_date;")
$sql.Add("ALTER TABLE public.product DROP COLUMN IF EXISTS expiry_managed;")
$sql.Add("")
$sql.Add("BEGIN;")
$sql.Add("")
$sql.Add("UPDATE public.warehouse_layout")
$sql.Add("SET name = U&'\B300\C804 \BB3C\B958\C13C\D130 A',")
$sql.Add("    location = U&'\B300\C804\AD11\C5ED\C2DC \C720\C131\AD6C',")
$sql.Add("    updated_at = NOW()")
$sql.Add("WHERE id = $WarehouseId;")
$sql.Add("")
$sql.Add("-- Retain referenced legacy rows, but only snapshot nodes remain active.")
$sql.Add("UPDATE public.warehouse_node SET is_active = FALSE WHERE warehouse_id = $WarehouseId;")
$sql.Add("")
$sql.Add("-- Active warehouse nodes: $($nodeRows.Count)")
$sql.Add("INSERT INTO public.warehouse_node (")
$sql.Add("    node_id, warehouse_id, zone_id, node_code, node_type, x, y,")
$sql.Add("    is_active, service_only, transit_allowed, holding_allowed, node_capacity,")
$sql.Add("    resource_type, resource_code, side, route_attributes")
$sql.Add(") VALUES")

$nodeValues = [System.Collections.Generic.List[string]]::new()
foreach ($row in $nodeRows) {
    $nodeValues.Add((
        "  ({0}, {1}, {2}, {3}, {4}, {5}, {6}, {7}, {8}, {9}, {10}, {11}, {12}, {13}, {14}, {15})" -f
        (SqlNumber $row.node_id),
        (SqlNumber $row.warehouse_id),
        (SqlText $row.zone_id),
        (SqlText $row.node_code),
        (SqlText $row.node_type),
        (SqlNumber $row.x),
        (SqlNumber $row.y),
        (SqlBoolean $row.is_active),
        (SqlBoolean $row.service_only),
        (SqlBoolean $row.transit_allowed),
        (SqlBoolean $row.holding_allowed),
        (SqlNumber $row.node_capacity),
        (SqlText $row.resource_type),
        (SqlText $row.resource_code),
        (SqlText $row.side),
        (SqlJson $row.route_attributes)
    ))
}
$sql.Add(($nodeValues -join ",`n"))
$sql.Add("ON CONFLICT (node_id) DO UPDATE SET")
$sql.Add("    warehouse_id = EXCLUDED.warehouse_id,")
$sql.Add("    zone_id = EXCLUDED.zone_id,")
$sql.Add("    node_code = EXCLUDED.node_code,")
$sql.Add("    node_type = EXCLUDED.node_type,")
$sql.Add("    x = EXCLUDED.x,")
$sql.Add("    y = EXCLUDED.y,")
$sql.Add("    is_active = EXCLUDED.is_active,")
$sql.Add("    service_only = EXCLUDED.service_only,")
$sql.Add("    transit_allowed = EXCLUDED.transit_allowed,")
$sql.Add("    holding_allowed = EXCLUDED.holding_allowed,")
$sql.Add("    node_capacity = EXCLUDED.node_capacity,")
$sql.Add("    resource_type = EXCLUDED.resource_type,")
$sql.Add("    resource_code = EXCLUDED.resource_code,")
$sql.Add("    side = EXCLUDED.side,")
$sql.Add("    route_attributes = EXCLUDED.route_attributes;")
$sql.Add("")

$edgeIds = ($edgeRows | ForEach-Object { [string]$_.edge_id }) -join ", "
$sql.Add("-- Remove edges retired by the current editor snapshot before applying the active set.")
$sql.Add("DELETE FROM public.warehouse_edge")
$sql.Add("WHERE from_node_id IN (SELECT node_id FROM public.warehouse_node WHERE warehouse_id = $WarehouseId)")
$sql.Add("  AND edge_id NOT IN ($edgeIds);")
$sql.Add("")
$sql.Add("-- Active warehouse edges: $($edgeRows.Count)")
$sql.Add("INSERT INTO public.warehouse_edge (")
$sql.Add("    edge_id, edge_code, from_node_id, to_node_id, distance, direction_type,")
$sql.Add("    edge_type, speed_limit_mps, nominal_travel_time_ms, cost,")
$sql.Add("    physical_resource_code, service_only, mobile_robot_traversable, route_attributes")
$sql.Add(") VALUES")

$edgeValues = [System.Collections.Generic.List[string]]::new()
foreach ($row in $edgeRows) {
    $edgeValues.Add((
        "  ({0}, {1}, {2}, {3}, {4}, {5}, {6}, {7}, {8}, {9}, {10}, {11}, {12}, {13})" -f
        (SqlNumber $row.edge_id),
        (SqlText $row.edge_code),
        (SqlNumber $row.from_node_id),
        (SqlNumber $row.to_node_id),
        (SqlNumber $row.distance),
        (SqlText $row.direction_type),
        (SqlText $row.edge_type),
        (SqlNumber $row.speed_limit_mps),
        (SqlNumber $row.nominal_travel_time_ms),
        (SqlNumber $row.cost),
        (SqlText $row.physical_resource_code),
        (SqlBoolean $row.service_only),
        (SqlBoolean $row.mobile_robot_traversable),
        (SqlJson $row.route_attributes)
    ))
}
$sql.Add(($edgeValues -join ",`n"))
$sql.Add("ON CONFLICT (edge_id) DO UPDATE SET")
$sql.Add("    edge_code = EXCLUDED.edge_code,")
$sql.Add("    from_node_id = EXCLUDED.from_node_id,")
$sql.Add("    to_node_id = EXCLUDED.to_node_id,")
$sql.Add("    distance = EXCLUDED.distance,")
$sql.Add("    direction_type = EXCLUDED.direction_type,")
$sql.Add("    edge_type = EXCLUDED.edge_type,")
$sql.Add("    speed_limit_mps = EXCLUDED.speed_limit_mps,")
$sql.Add("    nominal_travel_time_ms = EXCLUDED.nominal_travel_time_ms,")
$sql.Add("    cost = EXCLUDED.cost,")
$sql.Add("    physical_resource_code = EXCLUDED.physical_resource_code,")
$sql.Add("    service_only = EXCLUDED.service_only,")
$sql.Add("    mobile_robot_traversable = EXCLUDED.mobile_robot_traversable,")
$sql.Add("    route_attributes = EXCLUDED.route_attributes;")
$sql.Add("")
$sql.Add("SELECT setval(pg_get_serial_sequence('warehouse_node', 'node_id'),")
$sql.Add("    GREATEST((SELECT COALESCE(MAX(node_id), 1) FROM public.warehouse_node), 1), TRUE);")
$sql.Add("SELECT setval(pg_get_serial_sequence('warehouse_edge', 'edge_id'),")
$sql.Add("    GREATEST((SELECT COALESCE(MAX(edge_id), 1) FROM public.warehouse_edge), 1), TRUE);")
$sql.Add("")
$sql.Add("DO `$`$")
$sql.Add("DECLARE")
$sql.Add("    total_nodes integer;")
$sql.Add("    actual_nodes integer;")
$sql.Add("    actual_edges integer;")
$sql.Add("BEGIN")
$sql.Add("    SELECT count(*) INTO total_nodes")
$sql.Add("    FROM public.warehouse_node")
$sql.Add("    WHERE warehouse_id = $WarehouseId;")
$sql.Add("")
$sql.Add("    SELECT count(*) INTO actual_nodes")
$sql.Add("    FROM public.warehouse_node")
$sql.Add("    WHERE warehouse_id = $WarehouseId AND is_active = TRUE;")
$sql.Add("")
$sql.Add("    SELECT count(*) INTO actual_edges")
$sql.Add("    FROM public.warehouse_edge")
$sql.Add("    WHERE from_node_id IN (")
$sql.Add("        SELECT node_id FROM public.warehouse_node")
$sql.Add("        WHERE warehouse_id = $WarehouseId AND is_active = TRUE")
$sql.Add("    );")
$sql.Add("")
$sql.Add("    IF total_nodes <> $($nodeRows.Count) OR actual_nodes <> $activeNodeCount OR actual_edges <> $($edgeRows.Count) THEN")
$sql.Add("        RAISE EXCEPTION 'V09 warehouse map verification failed: total_nodes=%, active_nodes=%, edges=%',")
$sql.Add("            total_nodes, actual_nodes, actual_edges;")
$sql.Add("    END IF;")
$sql.Add("END")
$sql.Add("`$`$;")
$sql.Add("")
$sql.Add("COMMIT;")
$sql.Add("")

$resolvedOutput = [System.IO.Path]::GetFullPath($OutputPath)
[System.IO.Directory]::CreateDirectory([System.IO.Path]::GetDirectoryName($resolvedOutput)) | Out-Null
[System.IO.File]::WriteAllLines($resolvedOutput, $sql, [System.Text.UTF8Encoding]::new($false))

Write-Host "Wrote $resolvedOutput"
Write-Host "  warehouse: $WarehouseId"
Write-Host "  nodes:     $($nodeRows.Count)"
Write-Host "  active:    $activeNodeCount"
Write-Host "  edges:     $($edgeRows.Count)"

"""
창고 지도 JSON -> 시드 SQL 변환기

사용법
    python tools/generate_warehouse_seed.py \
        --json ../AI/data/warehouse_graph.json \
        --warehouse-id 1 \
        --name "대전 물류센터 A" \
        --out seed_warehouse_1.sql

왜 필요한가
    지도가 프론트 JSON 과 DB 두 곳에 있으면 언젠가 어긋난다.
    JSON 을 원본으로 두고 시드를 기계가 만들면 어긋날 수가 없다.

무엇을 하는가
    1. 통로/충전/입출고 노드를 그대로 옮긴다
    2. AI의 route/service access 노드를 손실 없이 저장한다
    3. 접근 노드 이름에서 PostgreSQL 재고용 랙 본체를 추가한다
    4. 방향성 간선과 경로 속성을 그대로 저장한다
    5. 충전소, 보관위치, 로봇을 노드에서 유도한다

Neo4j에는 랙 본체와 이동 불가 논리 노드를 제외한 방향성 경로 계약이
GraphSync를 통해 투영된다. 정확한 개수는 입력 JSON summary에서 확인한다.
"""

import argparse
import json
import re
from pathlib import Path

# JSON 노드 타입 -> 백엔드 NodeType
NODE_TYPE_MAP = {
    "route": "ROUTE",
    "route_charge_junction": "ROUTE_CHARGE_JUNCTION",
    "rack_access": "RACK_ACCESS",
    "inbound_handoff_access": "INBOUND_HANDOFF_ACCESS",
    "outbound_station_access": "OUTBOUND_STATION_ACCESS",
    "empty_tote_buffer_access": "EMPTY_TOTE_BUFFER_ACCESS",
    "inbound": "INBOUND",
    "outbound": "OUTBOUND",
    "charging_slot": "CHARGING_SLOT",
    "parking_slot": "PARKING_SLOT",
}

SERVICE_NODE_TYPES = {
    "rack_access",
    "inbound_handoff_access",
    "outbound_station_access",
    "empty_tote_buffer_access",
}

# 노드 타입 -> 구역
ZONE_BY_TYPE = {
    "ROUTE": "MOVING_ZONE",
    "ROUTE_CHARGE_JUNCTION": "MOVING_ZONE",
    "RACK_STORAGE": "STORAGE_ZONE",
    "RACK_ACCESS": "STORAGE_ZONE",
    "INBOUND_HANDOFF_ACCESS": "INBOUND_ZONE",
    "OUTBOUND_STATION_ACCESS": "OUTBOUND_ZONE",
    "EMPTY_TOTE_BUFFER_ACCESS": "MOVING_ZONE",
    "INBOUND": "INBOUND_ZONE",
    "OUTBOUND": "OUTBOUND_ZONE",
    "CHARGING_SLOT": "CHARGING_ZONE",
    "PARKING_SLOT": "CHARGING_ZONE",
}

ACCESS_PATTERN = re.compile(r"^(.+?)_ACCESS(?:_[A-Z])?$")

# 창고마다 ID 대역을 띄워 서로 겹치지 않게 한다
ID_BLOCK = 10_000


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--json", required=True, help="창고 지도 JSON 경로")
    parser.add_argument("--warehouse-id", type=int, required=True)
    parser.add_argument("--name", required=True, help="창고 이름")
    parser.add_argument("--location", default="대전광역시 유성구", help="소재지")
    parser.add_argument("--description", default="", help="설명")
    parser.add_argument("--out", required=True, help="출력 SQL 경로")
    parser.add_argument("--user-id", type=int, default=1, help="창고 소유자")
    parser.add_argument("--robots", type=int, default=6, help="로봇 대수")
    parser.add_argument(
        "--shared",
        action="store_true",
        help="공용 창고로 표시한다. 모두에게 보이고 수정·삭제할 수 없다.",
    )
    parser.add_argument(
        "--mode",
        choices=["insert", "upsert", "reset"],
        default="insert",
        help="insert: 없을 때만 넣음(앱 시작 시 자동 실행용) / "
             "upsert: 재고를 보존하며 기본 지도 계약을 갱신 / "
             "reset: 지우고 다시 넣음(지도가 바뀌었을 때)",
    )
    return parser.parse_args()


def load_graph(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def rack_code_of(node_id: str) -> str | None:
    """작업 자리 이름에서 랙 이름을 뽑는다. K0_1_ACCESS_A -> K0_1"""
    match = ACCESS_PATTERN.match(node_id)
    if not match:
        return None
    base = match.group(1)
    # 랙 접근 자리만 대상. 입출고/버퍼 자리는 랙이 아니다.
    return base if base.startswith("K") else None


def build_nodes(graph: dict, base_id: int):
    """저장할 노드 목록과 코드->ID 사전을 만든다."""
    nodes = []
    next_id = base_id + 1

    # 1) AI Neo4j RouteNode 계약을 그대로 옮긴다.
    for raw in graph["nodes"]:
        raw_type = raw.get("type", "route")
        node_type = NODE_TYPE_MAP.get(raw_type)
        if node_type is None:
            continue
        service_only = bool(raw.get("service_only", raw_type in SERVICE_NODE_TYPES))
        transit_allowed = bool(raw.get("transit_allowed", not service_only))
        holding_allowed = bool(raw.get("holding_allowed", True))
        node_capacity = int(raw.get("node_capacity", 1))
        resource_type = None
        resource_code = None
        if raw_type == "rack_access":
            resource_type, resource_code = "RACK", raw.get("rack_id")
        elif raw_type == "inbound_handoff_access":
            resource_type, resource_code = "INBOUND_HANDOFF", raw.get("handoff_id")
        elif raw_type == "outbound_station_access":
            resource_type, resource_code = "OUTBOUND_STATION", raw.get("station_id")
        elif raw_type == "empty_tote_buffer_access":
            resource_type, resource_code = "EMPTY_TOTE_BUFFER", raw.get("buffer_id")
        elif raw.get("resource_id") is not None:
            resource_type, resource_code = "RESOURCE", raw.get("resource_id")

        excluded = {
            "id", "type", "x", "y", "service_only", "transit_allowed",
            "holding_allowed", "node_capacity", "resource_type", "resource_code",
            "resource_id", "rack_id", "handoff_id", "station_id", "buffer_id",
            "side",
        }
        nodes.append({
            "id": next_id,
            "code": raw["id"],
            "type": node_type,
            "x": raw["x"],
            "y": raw["y"],
            "service_only": service_only,
            "transit_allowed": transit_allowed,
            "holding_allowed": holding_allowed,
            "node_capacity": node_capacity,
            "resource_type": resource_type,
            "resource_code": resource_code,
            "side": raw.get("side"),
            "route_attributes": {
                key: value for key, value in raw.items()
                if key not in excluded and value is not None
            },
        })
        next_id += 1

    # 2) 접근 자리에서 랙을 되살린다
    #    좌표는 양쪽 자리의 가운데로 잡는다
    rack_points: dict[str, list[tuple[float, float]]] = {}
    for raw in graph["nodes"]:
        if raw.get("type") != "rack_access":
            continue
        code = raw.get("rack_id") or rack_code_of(raw["id"])
        if code:
            rack_points.setdefault(code, []).append((raw["x"], raw["y"]))

    for code in sorted(rack_points):
        points = rack_points[code]
        nodes.append({
            "id": next_id,
            "code": code,
            "type": "RACK_STORAGE",
            "x": round(sum(p[0] for p in points) / len(points), 4),
            "y": round(sum(p[1] for p in points) / len(points), 4),
            "service_only": False,
            "transit_allowed": False,
            "holding_allowed": False,
            "node_capacity": 1,
            "resource_type": "RACK",
            "resource_code": code,
            "side": None,
            "route_attributes": {},
        })
        next_id += 1

    id_by_code = {n["code"]: n["id"] for n in nodes}
    return nodes, id_by_code


def build_edges(graph: dict, id_by_code: dict, base_id: int):
    """AI 경로 간선을 PostgreSQL 방향 모델에 손실 없이 저장한다."""
    edges = []
    next_id = base_id + 1

    for raw in graph["edges"]:
        source = raw.get("source")
        target = raw.get("target")
        if source not in id_by_code or target not in id_by_code or source == target:
            continue

        edge_type = raw.get("type") or "lane"
        service_only = bool(
            raw.get(
                "service_only",
                edge_type in {
                    "rack_access",
                    "inbound_handoff_access",
                    "outbound_station_access",
                    "empty_tote_buffer_access",
                    "service_spur",
                },
            )
        )
        distance = float(raw.get("distance_m") or raw.get("cost") or 0.0)
        speed = float(raw.get("speed_limit_mps") or 1.0)
        raw_direction = str(raw.get("direction") or "").strip().upper()
        direction = {
            "BOTH": "BOTH",
            "BIDIRECTIONAL": "BOTH",
            "A_TO_B": "A_TO_B",
            "B_TO_A": "B_TO_A",
        }.get(
            raw_direction,
            "BOTH" if edge_type == "charging_connector" else "A_TO_B",
        )
        excluded = {
            "id", "source", "target", "distance_m", "type", "direction",
            "speed_limit_mps", "nominal_travel_time_ms", "cost", "base_cost",
            "resource_id", "physical_resource_code", "service_only",
            "mobile_robot_traversable",
        }
        edges.append({
            "id": next_id,
            "code": raw["id"],
            "from": id_by_code[source],
            "to": id_by_code[target],
            "distance": distance,
            "direction": direction,
            "edge_type": edge_type,
            "speed_limit_mps": speed,
            "nominal_travel_time_ms": int(
                raw.get("nominal_travel_time_ms") or round(distance / speed * 1000)
            ),
            "cost": float(raw.get("cost") or distance),
            "physical_resource_code": raw.get("physical_resource_code")
                or raw.get("resource_id") or raw["id"],
            "service_only": service_only,
            "mobile_robot_traversable": bool(
                raw.get("mobile_robot_traversable", True)
            ),
            "route_attributes": {
                key: value for key, value in raw.items()
                if key not in excluded and value is not None
            },
        })
        next_id += 1

    return edges


def conflict(
        args,
        target: str,
        update_columns: tuple[str, ...],
) -> str:
    if args.mode == "reset":
        return ";"
    if args.mode == "insert":
        return "\nON CONFLICT DO NOTHING;"
    assignments = ", ".join(
        f"{column} = excluded.{column}" for column in update_columns
    )
    return f"\nON CONFLICT ({target}) DO UPDATE SET {assignments};"


def projected_traverses_count(edges: list[dict]) -> int:
    """Spring GraphSync가 실제 Neo4j에 생성할 방향성 관계 수."""

    return sum(
        2 if edge["direction"] == "BOTH" else 1
        for edge in edges
        if edge["mobile_robot_traversable"]
    )


def sql_literal(value) -> str:
    if value is None:
        return "NULL"
    if isinstance(value, str):
        return "'" + value.replace("'", "''") + "'"
    return str(value)


def build_sql(args, graph, nodes, edges) -> str:
    wid = args.warehouse_id
    base = wid * ID_BLOCK

    by_type = {}
    for node in nodes:
        by_type.setdefault(node["type"], []).append(node)

    xs = [n["x"] for n in nodes]
    ys = [n["y"] for n in nodes]

    out = []
    add = out.append

    warehouse_code = graph.get("warehouse_id") or f"WH-{wid:03d}"
    add(f"-- 창고 {wid} ({warehouse_code}) 시드")
    add(f"-- 원본: {Path(args.json).name}")
    add(f"-- {graph.get('title', '')}")
    add("-- 이 파일은 자동 생성됩니다. 직접 고치지 마세요.")
    add("-- 지도를 바꾸려면 JSON 을 고치고 스크립트를 다시 돌리세요.")
    add("")
    if args.mode in {"upsert", "reset"}:
        add("BEGIN;")
        add("")

    if args.mode == "reset":
        add("-- 이 창고의 기존 지도를 지운다. 다른 창고는 건드리지 않는다.")
        add("-- 시뮬레이션 기록도 함께 지워진다.")
        add(f"DELETE FROM task WHERE warehouse_id = {wid};")
        add(f"DELETE FROM warehouse_edge WHERE from_node_id IN "
            f"(SELECT node_id FROM warehouse_node WHERE warehouse_id = {wid});")
        add(f"DELETE FROM warehouse_items WHERE warehouse_id = {wid};")
        add(f"DELETE FROM storage_location WHERE warehouse_id = {wid};")
        add(f"DELETE FROM charging_station WHERE warehouse_id = {wid};")
        add(f"DELETE FROM robot WHERE warehouse_id = {wid};")
        add(f"DELETE FROM warehouse_node WHERE warehouse_id = {wid};")
        add(f"DELETE FROM warehouse_zone WHERE warehouse_id = {wid};")
        add("")

    if args.mode == "upsert":
        add("-- 기존 기본 지도의 코드 충돌을 피한 뒤 같은 PK에 새 계약을 덮어쓴다.")
        add(f"UPDATE warehouse_edge SET edge_code = "
            f"'__LARO_MIGRATION_EDGE_' || edge_id "
            f"WHERE edge_id BETWEEN {base + 1} AND {base + len(edges)} "
            f"AND from_node_id IN (SELECT node_id FROM warehouse_node "
            f"WHERE warehouse_id = {wid});")
        add(f"UPDATE warehouse_node SET node_code = "
            f"'__LARO_MIGRATION_NODE_' || node_id "
            f"WHERE warehouse_id = {wid} "
            f"AND node_id BETWEEN {base + 1} AND {base + len(nodes)};")
        add("")

    # 창고
    add("-- 창고")
    add("INSERT INTO warehouse_layout "
        "(id, name, width, height, user_id, location, description, status, "
        "is_shared, created_at, updated_at)")
    add(f"VALUES ({wid}, {sql_literal(args.name)}, "
        f"{round(max(xs) + 1)}, {round(max(ys) + 1)}, {args.user_id}, "
        f"{sql_literal(args.location)}, "
        f"{sql_literal(args.description or graph.get('title', ''))}, "
        f"'ACTIVE', {str(args.shared).lower()}, NOW(), NOW())")
    add("ON CONFLICT (id) DO UPDATE SET "
        "name = excluded.name, width = excluded.width, height = excluded.height, "
        "location = excluded.location, description = excluded.description, "
        "status = excluded.status, is_shared = excluded.is_shared, updated_at = NOW();"
        if args.mode in {"upsert", "reset"}
        else "ON CONFLICT (id) DO NOTHING;")
    add("")

    # 구역
    add("-- 구역 (노드 좌표에서 범위를 계산)")
    add("INSERT INTO warehouse_zone "
        "(zone_id, warehouse_id, name, zone_type, description, min_x, max_x, min_y, max_y) VALUES")
    zone_rows = []
    zone_meta = [
        ("MOVING_ZONE", "MOVING", "이동 통로", ["ROUTE", "ROUTE_CHARGE_JUNCTION"]),
        ("STORAGE_ZONE", "STORAGE", "랙 보관 구역", ["RACK_STORAGE", "RACK_ACCESS"]),
        ("INBOUND_ZONE", "INBOUND", "입고 구역", ["INBOUND", "INBOUND_HANDOFF_ACCESS"]),
        ("OUTBOUND_ZONE", "OUTBOUND", "출고 구역", ["OUTBOUND", "OUTBOUND_STATION_ACCESS"]),
        ("CHARGING_ZONE", "CHARGING", "충전 구역", ["CHARGING_SLOT", "PARKING_SLOT"]),
    ]
    for index, (name, zone_type, desc, types) in enumerate(zone_meta, start=1):
        members = [n for t in types for n in by_type.get(t, [])]
        if not members:
            continue
        zone_rows.append(
            f"  ({base + index}, {wid}, {sql_literal(name)}, {sql_literal(zone_type)}, "
            f"{sql_literal(desc)}, "
            f"{min(n['x'] for n in members)}, {max(n['x'] for n in members)}, "
            f"{min(n['y'] for n in members)}, {max(n['y'] for n in members)})"
        )
    add(",\n".join(zone_rows) + conflict(
        args,
        "zone_id",
        ("warehouse_id", "name", "zone_type", "description", "min_x", "max_x", "min_y", "max_y"),
    ))
    add("")

    # 노드
    add(f"-- 노드 {len(nodes)}개")
    add("INSERT INTO warehouse_node ("
        "node_id,warehouse_id,zone_id,node_code,node_type,x,y,"
        "service_only,transit_allowed,holding_allowed,node_capacity,"
        "resource_type,resource_code,side,route_attributes) VALUES")
    node_rows = [
        f"  ({n['id']}, {wid}, {sql_literal(ZONE_BY_TYPE[n['type']])}, "
        f"{sql_literal(n['code'])}, {sql_literal(n['type'])}, {n['x']}, {n['y']}, "
        f"{sql_literal(n['service_only'])}, {sql_literal(n['transit_allowed'])}, "
        f"{sql_literal(n['holding_allowed'])}, {n['node_capacity']}, "
        f"{sql_literal(n['resource_type'])}, {sql_literal(n['resource_code'])}, "
        f"{sql_literal(n['side'])}, "
        f"{sql_literal(json.dumps(n['route_attributes'], ensure_ascii=False))}::jsonb)"
        for n in nodes
    ]
    add(",\n".join(node_rows) + conflict(
        args,
        "node_id",
        (
            "warehouse_id", "zone_id", "node_code", "node_type", "x", "y",
            "service_only", "transit_allowed", "holding_allowed", "node_capacity",
            "resource_type", "resource_code", "side", "route_attributes",
        ),
    ))
    add("")

    # 간선
    add(f"-- 간선 {len(edges)}개")
    add("INSERT INTO warehouse_edge ("
        "edge_id,edge_code,from_node_id,to_node_id,distance,direction_type,"
        "edge_type,speed_limit_mps,nominal_travel_time_ms,cost,"
        "physical_resource_code,service_only,mobile_robot_traversable,route_attributes) VALUES")
    edge_rows = [
        f"  ({e['id']}, {sql_literal(e['code'])}, {e['from']}, {e['to']}, "
        f"{e['distance']}, {sql_literal(e['direction'])}, "
        f"{sql_literal(e['edge_type'])}, {e['speed_limit_mps']}, "
        f"{e['nominal_travel_time_ms']}, {e['cost']}, "
        f"{sql_literal(e['physical_resource_code'])}, {sql_literal(e['service_only'])}, "
        f"{sql_literal(e['mobile_robot_traversable'])}, "
        f"{sql_literal(json.dumps(e['route_attributes'], ensure_ascii=False))}::jsonb)"
        for e in edges
    ]
    add(",\n".join(edge_rows) + conflict(
        args,
        "edge_id",
        (
            "edge_code", "from_node_id", "to_node_id", "distance", "direction_type",
            "edge_type", "speed_limit_mps", "nominal_travel_time_ms", "cost",
            "physical_resource_code", "service_only", "mobile_robot_traversable",
            "route_attributes",
        ),
    ))
    if args.mode == "upsert":
        previous_edge_count = int(
            graph.get("previous_seed_edge_count", len(edges))
        )
        if previous_edge_count > len(edges):
            add("")
            add("-- 이전 시드에만 존재하던 후행 간선을 제거한다.")
            add(
                f"DELETE FROM warehouse_edge WHERE edge_id BETWEEN "
                f"{base + len(edges) + 1} AND {base + previous_edge_count} "
                f"AND from_node_id IN (SELECT node_id FROM warehouse_node "
                f"WHERE warehouse_id = {wid});"
            )
    add("")

    # 충전소
    slots = by_type.get("CHARGING_SLOT", [])
    if slots:
        add(f"-- 충전소 {len(slots)}개")
        add("INSERT INTO charging_station "
            "(charging_station_id, warehouse_id, node_id, name, status, charging_power) VALUES")
        rows = [
            f"  ({base + index}, {wid}, {n['id']}, "
            f"{sql_literal('충전소 ' + n['code'])}, 'AVAILABLE', 50.0)"
            for index, n in enumerate(slots, start=1)
        ]
        add(",\n".join(rows) + conflict(
            args,
            "charging_station_id",
            ("warehouse_id", "node_id", "name"),
        ))
        add("")

    # 보관위치
    racks = by_type.get("RACK_STORAGE", [])
    if racks:
        add(f"-- 보관위치 {len(racks)}개 (랙 노드마다 하나)")
        add("INSERT INTO storage_location "
            "(storage_location_id, warehouse_id, node_id, max_quantity, max_weight, max_volume, created_at, status) VALUES")
        rows = [
            f"  ({base + index}, {wid}, {n['id']}, 100, 1000, 1000, NOW(), 'AVAILABLE')"
            for index, n in enumerate(racks, start=1)
        ]
        add(",\n".join(rows) + conflict(
            args,
            "storage_location_id",
            ("warehouse_id", "node_id"),
        ))
        if args.mode == "upsert":
            add("")
            add("-- 기존 재고 행의 node_id도 보존된 보관위치가 가리키는 새 랙으로 맞춘다.")
            add("UPDATE warehouse_items AS wi SET node_id = sl.node_id "
                "FROM storage_location AS sl "
                "WHERE wi.storage_location_id = sl.storage_location_id "
                f"AND wi.warehouse_id = {wid} AND sl.warehouse_id = {wid};")
        add("")

    # 로봇 (충전 슬롯에 배치)
    if slots:
        count = min(args.robots, len(slots))
        add(f"-- 로봇 {count}대 (충전 슬롯에서 시작)")
        add("INSERT INTO robot (robot_id, robot_spec_id, warehouse_id, node_id, battery, status) VALUES")
        rows = [
            f"  ({base + index}, 1, {wid}, {slots[index - 1]['id']}, 100, 'AVAILABLE')"
            for index in range(1, count + 1)
        ]
        add(",\n".join(rows) + conflict(
            args,
            "robot_id",
            ("robot_spec_id", "warehouse_id", "node_id"),
        ))
        add("")

    # 시퀀스 정리
    add("-- 자동 증가 값을 최대 ID 뒤로 옮긴다")
    for table, column in [
        ("warehouse_node", "node_id"),
        ("warehouse_edge", "edge_id"),
        ("warehouse_zone", "zone_id"),
        ("charging_station", "charging_station_id"),
        ("storage_location", "storage_location_id"),
        ("robot", "robot_id"),
        ("warehouse_layout", "id"),
    ]:
        add(f"SELECT setval(pg_get_serial_sequence('{table}', '{column}'), "
            f"GREATEST((SELECT COALESCE(MAX({column}), 1) FROM {table}), 1), true);")
    if args.mode in {"upsert", "reset"}:
        add("")
        add("COMMIT;")
    add("")

    return "\n".join(out)


def main():
    args = parse_args()
    graph = load_graph(Path(args.json))

    base = args.warehouse_id * ID_BLOCK
    nodes, id_by_code = build_nodes(graph, base)
    edges = build_edges(graph, id_by_code, base)

    Path(args.out).write_text(build_sql(args, graph, nodes, edges), encoding="utf-8")

    kept = {}
    for node in nodes:
        kept[node["type"]] = kept.get(node["type"], 0) + 1

    skipped = sum(
        1 for node in graph["nodes"]
        if node.get("type", "route") not in NODE_TYPE_MAP
    )

    print(f"생성: {args.out}")
    print(f"  창고     {args.warehouse_id} ({graph.get('warehouse_id')})")
    print(f"  노드     {len(nodes)}개  (원본 {len(graph['nodes'])}, 제외 {skipped})")
    for node_type in sorted(kept):
        print(f"    {node_type:24} {kept[node_type]}")
    print(f"  간선     {len(edges)}개  (원본 {len(graph['edges'])})")
    print(
        f"    PostgreSQL {len(edges)}   "
        f"Neo4j TRAVERSES 예상 {projected_traverses_count(edges)}"
    )


if __name__ == "__main__":
    main()

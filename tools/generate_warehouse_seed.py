"""
창고 지도 JSON -> 시드 SQL 변환기

사용법
    python tools/generate_warehouse_seed.py \
        --json ../FE/src/data/warehouse_graph_1.json \
        --warehouse-id 1 \
        --name "대전 물류센터 A" \
        --out seed_warehouse_1.sql

왜 필요한가
    지도가 프론트 JSON 과 DB 두 곳에 있으면 언젠가 어긋난다.
    JSON 을 원본으로 두고 시드를 기계가 만들면 어긋날 수가 없다.

무엇을 하는가
    1. 통로/충전/입출고 노드를 그대로 옮긴다
    2. 접근 노드 이름에서 랙 노드를 되살린다  (K0_1_ACCESS_A -> K0_1)
    3. 접근 노드를 거치던 간선을 랙에 직접 잇는다
    4. 충전소, 보관위치, 로봇을 노드에서 유도한다

무엇을 하지 않는가
    작업 전용 노드(rack_access 등)는 저장하지 않는다.
    백엔드가 쓰지 않고, 노드 타입도 정의돼 있지 않다.
    화면에는 프론트 JSON 으로 계속 보인다.
"""

import argparse
import json
import re
from pathlib import Path

# JSON 노드 타입 -> 백엔드 NodeType
NODE_TYPE_MAP = {
    "route": "ROUTE",
    "route_charge_junction": "ROUTE_CHARGE_JUNCTION",
    "inbound": "INBOUND",
    "outbound": "OUTBOUND",
    "charging_slot": "CHARGING_SLOT",
}

# 저장하지 않는 타입 (작업 전용 자리)
SKIPPED_TYPES = {
    "rack_access",
    "inbound_access",
    "outbound_access",
    "empty_tote_buffer_access",
}

# 노드 타입 -> 구역
ZONE_BY_TYPE = {
    "ROUTE": "MOVING_ZONE",
    "ROUTE_CHARGE_JUNCTION": "MOVING_ZONE",
    "RACK_STORAGE": "STORAGE_ZONE",
    "INBOUND": "INBOUND_ZONE",
    "OUTBOUND": "OUTBOUND_ZONE",
    "CHARGING_SLOT": "CHARGING_ZONE",
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
        choices=["insert", "reset"],
        default="insert",
        help="insert: 없을 때만 넣음(앱 시작 시 자동 실행용) / "
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

    # 1) 그대로 옮기는 노드
    for raw in graph["nodes"]:
        node_type = NODE_TYPE_MAP.get(raw.get("type"))
        if node_type is None:
            continue
        nodes.append({
            "id": next_id,
            "code": raw["id"],
            "type": node_type,
            "x": raw["x"],
            "y": raw["y"],
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
        })
        next_id += 1

    id_by_code = {n["code"]: n["id"] for n in nodes}
    return nodes, id_by_code


def build_edges(graph: dict, id_by_code: dict, base_id: int):
    """
    간선을 옮긴다.

    접근 자리를 거치던 간선은 랙에 직접 잇는다.
        R0_1 -> K0_1_ACCESS_A   =>   R0_1 -> K0_1
    저장하지 않는 자리로 가는 나머지 간선은 버린다.
    """
    access_to_rack = {}
    for raw in graph["nodes"]:
        if raw.get("type") != "rack_access":
            continue
        code = raw.get("rack_id") or rack_code_of(raw["id"])
        if code:
            access_to_rack[raw["id"]] = code

    def resolve(code: str) -> str | None:
        if code in id_by_code:
            return code
        return access_to_rack.get(code)

    # 방향별로 모은다.
    #   A->B 와 B->A 가 둘 다 있으면  BOTH 한 줄
    #   한쪽만 있으면                 A_TO_B 한 줄
    #
    # JSON 은 왕복 통로를 두 줄(IN/OUT)로 적는다.
    # 그대로 옮기면 같은 통로가 두 번 들어가 경로 계산이 헷갈린다.
    directed = {}
    for raw in graph["edges"]:
        source = resolve(raw["source"])
        target = resolve(raw["target"])

        if source is None or target is None or source == target:
            continue

        directed.setdefault((source, target), raw)

    edges = []
    used = set()
    next_id = base_id + 1

    for (source, target), raw in directed.items():
        if (source, target) in used or (target, source) in used:
            continue

        two_way = (target, source) in directed
        used.add((source, target))

        edges.append({
            "id": next_id,
            "code": clean_edge_code(raw["id"], two_way),
            "from": id_by_code[source],
            "to": id_by_code[target],
            "distance": raw.get("distance_m", 1.0),
            "direction": "BOTH" if two_way else "A_TO_B",
        })
        next_id += 1

    return edges


def clean_edge_code(code: str, two_way: bool) -> str:
    """왕복 간선은 방향 접미사를 뗀다. RA_K0_1_A_IN -> RA_K0_1_A"""
    if two_way:
        for suffix in ("_IN", "_OUT"):
            if code.endswith(suffix):
                return code[: -len(suffix)]
    return code


def conflict(args) -> str:
    """
    자동 실행 모드에서는 이미 있는 행을 건드리지 않는다.

    앱이 켜질 때마다 돌아가므로, 덮어쓰면 시뮬레이션 중 바뀐 값이 되돌아간다.
    """
    return "\nON CONFLICT DO NOTHING;" if args.mode == "insert" else ";"


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

    add(f"-- 창고 {wid} ({graph.get('warehouse_id')}) 시드")
    add(f"-- 원본: {Path(args.json).name}")
    add(f"-- {graph.get('title', '')}")
    add("-- 이 파일은 자동 생성됩니다. 직접 고치지 마세요.")
    add("-- 지도를 바꾸려면 JSON 을 고치고 스크립트를 다시 돌리세요.")
    add("")
    if args.mode == "reset":
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
    add("ON CONFLICT (id) DO UPDATE SET name = excluded.name, "
        "width = excluded.width, height = excluded.height;"
        if args.mode == "reset" else "ON CONFLICT (id) DO NOTHING;")
    add("")

    # 구역
    add("-- 구역 (노드 좌표에서 범위를 계산)")
    add("INSERT INTO warehouse_zone "
        "(zone_id, warehouse_id, name, zone_type, description, min_x, max_x, min_y, max_y) VALUES")
    zone_rows = []
    zone_meta = [
        ("MOVING_ZONE", "MOVING", "이동 통로", ["ROUTE", "ROUTE_CHARGE_JUNCTION"]),
        ("STORAGE_ZONE", "STORAGE", "랙 보관 구역", ["RACK_STORAGE"]),
        ("INBOUND_ZONE", "INBOUND", "입고 구역", ["INBOUND"]),
        ("OUTBOUND_ZONE", "OUTBOUND", "출고 구역", ["OUTBOUND"]),
        ("CHARGING_ZONE", "CHARGING", "충전 구역", ["CHARGING_SLOT"]),
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
    add(",\n".join(zone_rows) + conflict(args))
    add("")

    # 노드
    add(f"-- 노드 {len(nodes)}개")
    add("INSERT INTO warehouse_node (node_id, warehouse_id, zone_id, node_code, node_type, x, y) VALUES")
    node_rows = [
        f"  ({n['id']}, {wid}, {sql_literal(ZONE_BY_TYPE[n['type']])}, "
        f"{sql_literal(n['code'])}, {sql_literal(n['type'])}, {n['x']}, {n['y']})"
        for n in nodes
    ]
    add(",\n".join(node_rows) + conflict(args))
    add("")

    # 간선
    add(f"-- 간선 {len(edges)}개")
    add("INSERT INTO warehouse_edge (edge_id, edge_code, from_node_id, to_node_id, distance, direction_type) VALUES")
    edge_rows = [
        f"  ({e['id']}, {sql_literal(e['code'])}, {e['from']}, {e['to']}, "
        f"{e['distance']}, {sql_literal(e['direction'])})"
        for e in edges
    ]
    add(",\n".join(edge_rows) + conflict(args))
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
        add(",\n".join(rows) + conflict(args))
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
        add(",\n".join(rows) + conflict(args))
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
        add(",\n".join(rows) + conflict(args))
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
    if args.mode == "reset":
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

    skipped = sum(1 for n in graph["nodes"] if n.get("type") in SKIPPED_TYPES)

    print(f"생성: {args.out}")
    print(f"  창고     {args.warehouse_id} ({graph.get('warehouse_id')})")
    print(f"  노드     {len(nodes)}개  (원본 {len(graph['nodes'])}, 제외 {skipped})")
    for node_type in sorted(kept):
        print(f"    {node_type:24} {kept[node_type]}")
    two_way = sum(1 for e in edges if e["direction"] == "BOTH")
    print(f"  간선     {len(edges)}개  (원본 {len(graph['edges'])})")
    print(f"    양방향 {two_way}   단방향 {len(edges) - two_way}")


if __name__ == "__main__":
    main()

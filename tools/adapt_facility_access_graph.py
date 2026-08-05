"""Adapt the expanded facility-access map to the LARO BE/AI route contract.

The reference map represents logical inbound/outbound associations as route
edges and lets one access node touch several aisle nodes.  LARO deliberately
keeps those concerns separate:

* Neo4j contains only mobile-robot traversable edges.
* A handoff/station has two service-only, one-neighbour access nodes.
* Business ports/chutes are logical facilities, not robot destinations.

This utility preserves the reference map's route/rack/charging geometry and
creates a canonical graph plus matching facility master data.
"""

from __future__ import annotations

import argparse
import copy
import json
import math
from pathlib import Path
from typing import Any


INBOUND_ACCESS = (
    ("I_0", "IN_HANDOFF_1_ACCESS_A", "IN_HANDOFF_1", "A", "R1_0", ["I_a"]),
    ("I_1", "IN_HANDOFF_1_ACCESS_B", "IN_HANDOFF_1", "B", "R2_0", ["I_a", "I_b", "I_c"]),
    ("I_2", "IN_HANDOFF_2_ACCESS_A", "IN_HANDOFF_2", "A", "R3_0", ["I_b", "I_c", "I_d"]),
    ("I_3", "IN_HANDOFF_2_ACCESS_B", "IN_HANDOFF_2", "B", "R4_0", ["I_d", "I_e", "I_f"]),
    ("I_4", "IN_HANDOFF_3_ACCESS_A", "IN_HANDOFF_3", "A", "R5_0", ["I_e", "I_f", "I_g"]),
    ("I_5", "IN_HANDOFF_3_ACCESS_B", "IN_HANDOFF_3", "B", "R6_0", ["I_g"]),
)

OUTBOUND_STATIONS = (
    ("O_0", "OUT_STATION_1", "SR-OUT-01", ("R1_10", "R2_10"), ["O_A", "O_B", "O_C"]),
    ("O_1", "OUT_STATION_2", "SR-OUT-02", ("R3_10", "R4_10"), ["O_C", "O_D", "O_E"]),
    ("O_2", "OUT_STATION_3", "SR-OUT-03", ("R5_10", "R6_10"), ["O_E", "O_F", "O_G"]),
)


def read_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def directed_service_edge(
    edge_id: str,
    source: str,
    target: str,
    edge_type: str,
    resource_id: str,
    node_by_id: dict[str, dict[str, Any]],
    movement_role: str,
) -> dict[str, Any]:
    left = node_by_id[source]
    right = node_by_id[target]
    distance = round(
        math.hypot(float(left["x"]) - float(right["x"]), float(left["y"]) - float(right["y"]))
        * 2.5,
        6,
    )
    return {
        "id": edge_id,
        "source": source,
        "target": target,
        "type": edge_type,
        "resource_id": resource_id,
        "service_only": True,
        "mobile_robot_traversable": True,
        "distance_m": distance,
        "speed_limit_mps": 1.0,
        "nominal_travel_time_ms": round(distance * 1000),
        "cost": distance,
        "movement_role": movement_role,
    }


def legacy_compatibility_edge(
    edge_id: str,
    source: str,
    target: str,
    edge_type: str,
    node_by_id: dict[str, dict[str, Any]],
) -> dict[str, Any]:
    left = node_by_id[source]
    right = node_by_id[target]
    distance = round(
        math.hypot(float(left["x"]) - float(right["x"]), float(left["y"]) - float(right["y"]))
        * 2.5,
        6,
    )
    return {
        "id": edge_id,
        "source": source,
        "target": target,
        "type": edge_type,
        "service_only": False,
        "mobile_robot_traversable": True,
        "active_for_new_work": False,
        "legacy_direct_route": True,
        "distance_m": distance,
        "speed_limit_mps": 1.0,
        "nominal_travel_time_ms": round(distance * 1000),
        "cost": distance,
    }


def canonical_graph(source: dict[str, Any]) -> dict[str, Any]:
    source_nodes = {str(value["id"]): value for value in source["nodes"]}

    common_nodes: list[dict[str, Any]] = []
    for node in source["nodes"]:
        node_type = str(node.get("type") or "route")
        if node_type in {"inbound_access", "outbound_access", "empty_tote_buffer_access"}:
            continue
        value = copy.deepcopy(node)
        if node_type in {"inbound", "outbound"}:
            value.update(
                {
                    "service_only": False,
                    # Direct-I/O compatibility still needs these nodes in the
                    # route projection. New work uses facility handoffs/stations.
                    "transit_allowed": True,
                    "holding_allowed": False,
                    "mobile_robot_destination": False,
                    "mobile_routing_excluded": True,
                    "routing_role": "logical_facility_port",
                }
            )
        common_nodes.append(value)

    type_order = {
        "route": 0,
        "route_charge_junction": 1,
        "inbound": 2,
        "outbound": 3,
        "charging_slot": 4,
        "rack_access": 5,
    }
    original_position = {
        str(value["id"]): index for index, value in enumerate(source["nodes"])
    }
    common_nodes.sort(
        key=lambda value: (
            type_order.get(str(value.get("type")), 99),
            original_position[str(value["id"])],
        )
    )

    inbound_nodes: list[dict[str, Any]] = []
    for source_id, node_id, handoff_id, side, adjacent, display_ports in INBOUND_ACCESS:
        raw = source_nodes[source_id]
        inbound_nodes.append(
            {
                "id": node_id,
                "type": "inbound_handoff_access",
                "resource_id": handoff_id,
                "handoff_id": handoff_id,
                "side": side,
                "service_only": True,
                "transit_allowed": False,
                "holding_allowed": False,
                "node_capacity": 1,
                "adjacent_route_node": adjacent,
                "display_port_ids": display_ports,
                "x": raw["x"],
                "y": raw["y"],
            }
        )

    outbound_nodes: list[dict[str, Any]] = []
    for source_id, station_id, _robot_id, adjacent_nodes, destinations in OUTBOUND_STATIONS:
        raw = source_nodes[source_id]
        for side, y_offset, adjacent in zip(("A", "B"), (-0.16, 0.16), adjacent_nodes):
            outbound_nodes.append(
                {
                    "id": f"{station_id}_ACCESS_{side}",
                    "type": "outbound_station_access",
                    "resource_id": station_id,
                    "station_id": station_id,
                    "side": side,
                    "service_only": True,
                    "transit_allowed": False,
                    "holding_allowed": False,
                    "node_capacity": 1,
                    "adjacent_route_node": adjacent,
                    "display_chute_ids": destinations,
                    "x": raw["x"],
                    "y": round(float(raw["y"]) + y_offset, 4),
                }
            )

    buffer_raw = source_nodes.get("ETB_0") or source_nodes.get("EMPTY_TOTE_BUFFER_1_ACCESS")
    if buffer_raw is None:
        raise ValueError("Reference graph has no empty-tote-buffer access node")
    buffer_node = {
        "id": "EMPTY_TOTE_BUFFER_1_ACCESS",
        "type": "empty_tote_buffer_access",
        "resource_id": "EMPTY_TOTE_BUFFER_1",
        "buffer_id": "EMPTY_TOTE_BUFFER_1",
        "service_only": True,
        "transit_allowed": False,
        "holding_allowed": False,
        "node_capacity": 1,
        "adjacent_route_node": "R6_10",
        "x": buffer_raw["x"],
        "y": buffer_raw["y"],
    }

    nodes = [*common_nodes, *inbound_nodes, *outbound_nodes, buffer_node]
    node_by_id = {str(value["id"]): value for value in nodes}

    edges: list[dict[str, Any]] = []
    allowed_base_types = {
        "lane",
        "spine",
        "rack_access",
        "charging_connector",
        "return_perimeter",
        "empty_tote_buffer_access",
        "inbound_elevator",
        "outbound_stations",
    }
    for edge in source["edges"]:
        edge_type = str(edge.get("type") or "lane")
        if edge_type not in allowed_base_types:
            continue
        if edge_type == "spine" and (
            not str(edge.get("source", "")).startswith("R")
            or not str(edge.get("target", "")).startswith("R")
        ):
            continue
        if edge_type == "charging_connector" and str(edge.get("id", "")).endswith("_RETURN"):
            continue
        value = copy.deepcopy(edge)
        if edge_type == "charging_connector":
            # The reference draws entry/return as two directed records. LARO's
            # PostgreSQL model stores the same physical connector once with
            # direction=BOTH; Spring GraphSync expands it to two TRAVERSES.
            value["direction"] = "BOTH"
            value["movement_role"] = "charging_entry_exit"
        if value.get("source") == "ETB_0":
            value["source"] = "EMPTY_TOTE_BUFFER_1_ACCESS"
        if value.get("target") == "ETB_0":
            value["target"] = "EMPTY_TOTE_BUFFER_1_ACCESS"
        if value.get("resource_id") == "ETB_0":
            value["resource_id"] = "EMPTY_TOTE_BUFFER_1"
        if value["source"] not in node_by_id or value["target"] not in node_by_id:
            continue
        if edge_type in {"inbound_elevator", "outbound_stations"}:
            value["active_for_new_work"] = False
            value["legacy_direct_route"] = True
        edges.append(value)

    edges.extend(
        (
            legacy_compatibility_edge(
                "IN_CONNECT", "I_d", "R3_0", "inbound_connector", node_by_id
            ),
            legacy_compatibility_edge(
                "OUT_CONNECT", "R3_10", "O_D", "outbound_connector", node_by_id
            ),
        )
    )

    for node in inbound_nodes:
        access = str(node["id"])
        route = str(node["adjacent_route_node"])
        handoff = str(node["handoff_id"])
        edges.extend(
            (
                directed_service_edge(
                    f"IH_{access}_IN", route, access, "inbound_handoff_access", handoff,
                    node_by_id, "handoff_entry",
                ),
                directed_service_edge(
                    f"IH_{access}_OUT", access, route, "inbound_handoff_access", handoff,
                    node_by_id, "handoff_exit",
                ),
            )
        )

    for node in outbound_nodes:
        access = str(node["id"])
        route = str(node["adjacent_route_node"])
        station = str(node["station_id"])
        edges.extend(
            (
                directed_service_edge(
                    f"OS_{access}_IN", route, access, "outbound_station_access", station,
                    node_by_id, "station_entry",
                ),
                directed_service_edge(
                    f"OS_{access}_OUT", access, route, "outbound_station_access", station,
                    node_by_id, "station_exit",
                ),
            )
        )

    graph = copy.deepcopy(source)
    graph["title"] = "자동창고 노드-엣지 구조 - 확장 입출고 접근형"
    graph["nodes"] = nodes
    graph["edges"] = edges
    graph["summary"] = {
        "node_count": len(nodes),
        "edge_count": len(edges),
        "route_grid_nodes": sum(value.get("type") == "route" for value in nodes),
        "route_charge_junction_nodes": sum(value.get("type") == "route_charge_junction" for value in nodes),
        "inbound_nodes": sum(value.get("type") == "inbound" for value in nodes),
        "outbound_nodes": sum(value.get("type") == "outbound" for value in nodes),
        "charging_slot_nodes": sum(value.get("type") == "charging_slot" for value in nodes),
        "rack_access_nodes": sum(value.get("type") == "rack_access" for value in nodes),
        "rack_entities_external": 48,
        "rack_storage_nodes": 0,
        "rack_entity_count": 48,
        "routing_projection_excludes_racks": True,
        "inbound_handoff_access_nodes": len(inbound_nodes),
        "inbound_handoff_count": 3,
        "outbound_station_access_nodes": len(outbound_nodes),
        "outbound_station_count": 3,
        "station_robot_count": 3,
        "return_perimeter_edges": sum(value.get("type") == "return_perimeter" for value in edges),
        "empty_tote_buffer_access_nodes": 1,
        "logical_outbound_destinations": 7,
    }
    graph["facility_routing_model"] = {
        "inbound_business_ports_in_facility_catalog": True,
        "inbound_handoff_access_node_type": "inbound_handoff_access",
        "outbound_business_chutes_in_facility_catalog": True,
        "outbound_station_access_node_type": "outbound_station_access",
        "mobile_robot_delivers_handling_unit_to_station": True,
        "station_robot_sorts_to_order_chutes": True,
        "legacy_direct_io_nodes_active_for_new_work": False,
        "outbound_return_perimeter": True,
        "return_perimeter_direction": "counter_clockwise",
        "logical_outbound_destinations": [f"O_{value}" for value in "ABCDEFG"],
        "station_is_mandatory_processing_waypoint": True,
        "amr_physical_destination_is_station_access": True,
        "station_robot_routes_to_logical_destination": True,
        "all_stations_serve_all_destinations": False,
        "destination_assignment_mode": "zoned_with_overlap",
        "station_destination_map": {
            station_id: destinations
            for _source, station_id, _robot, _adjacent, destinations in OUTBOUND_STATIONS
        },
        "empty_tote_buffer_access_node_type": "empty_tote_buffer_access",
        "charging_slot_connectors_bidirectional": True,
        "exclude_inactive_legacy_nodes_from_mobile_graph": True,
    }
    graph["distance_source"] = "euclidean_from_node_coordinates_v1_contract_adapted"
    graph["previous_seed_edge_count"] = 356
    graph["warehouse_id"] = "WH-001"
    return graph


def facility_document() -> dict[str, Any]:
    ports = []
    for label, handoff_id in (
        ("a", "IN_HANDOFF_1"),
        ("b", "IN_HANDOFF_1"),
        ("c", "IN_HANDOFF_1"),
        ("d", "IN_HANDOFF_2"),
        ("e", "IN_HANDOFF_2"),
        ("f", "IN_HANDOFF_3"),
        ("g", "IN_HANDOFF_3"),
    ):
        ports.append({"port_id": f"I_{label}", "label": label, "handoff_id": handoff_id})

    handoffs = []
    for index in range(1, 4):
        handoff_id = f"IN_HANDOFF_{index}"
        handoffs.append(
            {
                "handoff_id": handoff_id,
                "access_node_ids": [
                    f"{handoff_id}_ACCESS_A",
                    f"{handoff_id}_ACCESS_B",
                ],
                "buffer_capacity": 2,
            }
        )

    stations = []
    robots = []
    for _source, station_id, robot_id, _adjacent, destinations in OUTBOUND_STATIONS:
        stations.append(
            {
                "station_id": station_id,
                "station_robot_id": robot_id,
                "access_node_ids": [
                    f"{station_id}_ACCESS_A",
                    f"{station_id}_ACCESS_B",
                ],
                "served_chute_ids": destinations,
                "tote_buffer_capacity": 2,
                "status": "available",
                "serves_all_destinations": False,
            }
        )
        robots.append(
            {
                "station_robot_id": robot_id,
                "station_id": station_id,
                "status": "idle",
                "max_orders_per_wave": 16,
                "items_per_tick": 1,
            }
        )

    return {
        "version": "14.0",
        "updated_at": "2026-08-01T00:00:00Z",
        "routing_contract": {
            "business_ports_are_not_mobile_robot_destinations": True,
            "inbound_uses_handoff_access_nodes": True,
            "outbound_uses_goods_to_person_stations": True,
            "station_robot_sorts_one_handling_unit_to_many_orders": True,
            "remainder_returns_to_home_rack_when_positive": True,
            "orders_keep_logical_outbound_destination": True,
            "station_is_mandatory_processing_waypoint": True,
            "stations_are_not_fixed_on_orders": True,
            "all_stations_serve_all_outbound_destinations": False,
            "destination_assignment_mode": "zoned_with_overlap",
            "station_robot_routes_units_to_logical_destinations": True,
            "depleted_handling_unit_moves_to_empty_tote_buffer": True,
        },
        "inbound_ports": ports,
        "inbound_handoffs": handoffs,
        "outbound_chutes": [
            {"chute_id": f"O_{label}", "label": label} for label in "ABCDEFG"
        ],
        "outbound_stations": stations,
        "station_robots": robots,
        "empty_tote_buffers": [
            {
                "buffer_id": "EMPTY_TOTE_BUFFER_1",
                "access_node_ids": ["EMPTY_TOTE_BUFFER_1_ACCESS"],
                "capacity": 32,
                "status": "available",
            }
        ],
        "warehouse_id": "WH-001",
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--graph-out", type=Path, required=True)
    parser.add_argument("--facility-out", type=Path, required=True)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    graph = canonical_graph(read_json(args.source))
    facilities = facility_document()
    write_json(args.graph_out, graph)
    write_json(args.facility_out, facilities)
    print(
        json.dumps(
            {
                "graph_out": str(args.graph_out),
                "facility_out": str(args.facility_out),
                "nodes": len(graph["nodes"]),
                "edges": len(graph["edges"]),
                "inbound_handoffs": len(facilities["inbound_handoffs"]),
                "outbound_stations": len(facilities["outbound_stations"]),
            },
            ensure_ascii=False,
            indent=2,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

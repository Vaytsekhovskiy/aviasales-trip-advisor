"""Re-encode the trained sklearn forest as an ONNX graph with tensor-backed trees."""

import argparse
from pathlib import Path

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper

ML = "ai.onnx.ml"


def values(field, dtype=np.int32):
    return np.fromiter(field, dtype=dtype, count=len(field))


def forest_arrays(node):
    attrs = {attr.name: attr for attr in node.attribute}
    if (attrs["n_targets"].i != 1 or attrs["post_transform"].s != b"NONE"
            or attrs.get("aggregate_function") is not None and attrs["aggregate_function"].s != b"SUM"
            or "base_values" in attrs or "base_values_as_tensor" in attrs):
        raise ValueError("Expected a scalar SUM forest without base values or post-transform")

    branches = {b"BRANCH_LEQ": False, b"LEAF": True}
    modes = attrs["nodes_modes"].strings
    leaf = np.fromiter(map(branches.__getitem__, modes), dtype=np.bool_, count=len(modes))
    interior = np.flatnonzero(~leaf).astype(np.int32)
    leaves = np.flatnonzero(leaf).astype(np.int32)
    tree_ids = values(attrs["nodes_treeids"].ints)
    starts = np.flatnonzero(np.r_[True, tree_ids[1:] != tree_ids[:-1]]).astype(np.int32)
    if (not len(interior) or not np.array_equal(tree_ids[starts], np.arange(starts.size))
            or not np.array_equal(np.arange(len(tree_ids), dtype=np.int32) - starts[tree_ids],
                                  values(attrs["nodes_nodeids"].ints))):
        raise ValueError("Expected ordered trees with contiguous node IDs")

    # Branch references are nonnegative; ~reference is the leaf index for a negative reference.
    refs = np.empty(len(leaf), dtype=np.int32)
    refs[interior] = np.arange(len(interior), dtype=np.int32)
    refs[leaves] = ~np.arange(len(leaves), dtype=np.int32)
    tree_start = starts[tree_ids[interior]]
    target_nodes = starts[values(attrs["target_treeids"].ints)] + values(attrs["target_nodeids"].ints)
    leaf_indices = ~refs[target_nodes]
    if (len(leaf_indices) != len(leaves) or not leaf[target_nodes].all()
            or len(np.unique(leaf_indices)) != len(leaves) or any(attrs["target_ids"].ints)):
        raise ValueError("Expected exactly one scalar target weight per leaf")
    weights = np.empty(len(leaves), dtype=np.float32)
    weights[leaf_indices] = values(attrs["target_weights"].floats, np.float32)
    arrays = {
        "roots": refs[starts],
        "features": values(attrs["nodes_featureids"].ints)[interior],
        "thresholds": values(attrs["nodes_values"].floats, np.float32)[interior],
        "true_refs": refs[tree_start + values(attrs["nodes_truenodeids"].ints)[interior]],
        "false_refs": refs[tree_start + values(attrs["nodes_falsenodeids"].ints)[interior]],
        "weights": weights,
        "max_steps": np.asarray(np.diff(starts, append=len(leaf)).max() + 1, dtype=np.int64),
    }
    if "nodes_missing_value_tracks_true" in attrs:
        accepts_missing = values(attrs["nodes_missing_value_tracks_true"].ints, np.bool_)[interior]
        if accepts_missing.any():
            arrays["accepts_missing"] = accepts_missing
    return arrays


def encode_forest(source, arrays):
    prefix = "__avia_forest_"
    feature_matrix = source.input[0]
    roots = len(arrays["roots"])
    constants = {
        **arrays,
        "zero": np.asarray(0, dtype=np.int32),
        "minus_one": np.asarray(-1, dtype=np.int32),
        "first_axis": np.asarray([0], dtype=np.int64),
        "tree_axis": np.asarray([1], dtype=np.int64),
        "batch_index": np.asarray(0, dtype=np.int64),
        "tree_count": np.asarray([roots], dtype=np.int64),
        "enter": np.asarray(True, dtype=np.bool_),
    }
    initializers = [numpy_helper.from_array(array, prefix + name) for name, array in constants.items()]

    def op(kind, inputs, output, **attributes):
        return helper.make_node(kind, [prefix + key for key in inputs], [prefix + output], **attributes)

    def info(name, dtype, shape):
        return helper.make_tensor_value_info(prefix + name, dtype, shape)

    body = [
        op("Less", ["current", "zero"], "at_leaf"),
        op("Where", ["at_leaf", "zero", "current"], "safe_index"),
        op("Gather", ["features", "safe_index"], "test_features"),
        helper.make_node("GatherElements", [feature_matrix, prefix + "test_features"], [prefix + "feature_values"], axis=1),
        op("Gather", ["thresholds", "safe_index"], "test_thresholds"),
        op("LessOrEqual", ["feature_values", "test_thresholds"], "leq"),
    ]
    compare = "leq"
    if "accepts_missing" in arrays:
        body.extend([
            op("IsNaN", ["feature_values"], "missing"),
            op("Gather", ["accepts_missing", "safe_index"], "test_missing"),
            op("Where", ["missing", "test_missing", "leq"], "follow_true"),
        ])
        compare = "follow_true"
    body.extend([
        op("Gather", ["true_refs", "safe_index"], "true_child"),
        op("Gather", ["false_refs", "safe_index"], "false_child"),
        op("Where", [compare, "true_child", "false_child"], "child"),
        op("Where", ["at_leaf", "current", "child"], "next"),
        op("ReduceMax", ["next"], "max_ref", keepdims=0),
        op("GreaterOrEqual", ["max_ref", "zero"], "has_branches"),
    ])
    shape = [None, roots]
    loop = helper.make_graph(body, prefix + "walk",
        [info("iteration", TensorProto.INT64, []), info("enabled", TensorProto.BOOL, []),
         info("current", TensorProto.INT32, shape)],
        [info("has_branches", TensorProto.BOOL, []), info("next", TensorProto.INT32, shape)])
    graph = [
        helper.make_node("Shape", [feature_matrix], [prefix + "feature_shape"]),
        op("Gather", ["feature_shape", "batch_index"], "batch_size"),
        op("Unsqueeze", ["batch_size", "first_axis"], "batch_vector"),
        op("Concat", ["batch_vector", "tree_count"], "root_shape", axis=0),
        op("Expand", ["roots", "root_shape"], "root_refs"),
        op("Loop", ["max_steps", "enter", "root_refs"], "leaf_refs", body=loop),
        op("Sub", ["minus_one", "leaf_refs"], "leaf_indices"),
        op("Gather", ["weights", "leaf_indices"], "tree_scores"),
        helper.make_node("ReduceSum", [prefix + "tree_scores", prefix + "tree_axis"], list(source.output), keepdims=1),
    ]
    return graph, initializers


def compact_model(source, destination):
    if source.resolve() == destination.resolve() or destination.exists():
        raise ValueError("Provide a new output path to preserve the source artifact")
    print("Reading the original ONNX model.", flush=True)
    model = onnx.load(source)
    core = next(item for item in model.opset_import if item.domain == "")
    if core.version < 13:
        operators = {node.op_type for node in model.graph.node if node.domain == ""}
        if operators - {"Cast", "Concat", "Reshape"}:
            raise ValueError("The source preprocessing graph is incompatible with ONNX opset 13")
        core.version = 13
    forests = [i for i, node in enumerate(model.graph.node) if node.domain == ML and node.op_type == "TreeEnsembleRegressor"]
    if len(forests) != 1 or forests[0] != len(model.graph.node) - 1:
        raise ValueError("Expected one TreeEnsembleRegressor at the output of the graph")
    print("Encoding the original tree thresholds and weights as contiguous tensors.", flush=True)
    forest = model.graph.node[-1]
    nodes, initializers = encode_forest(forest, forest_arrays(forest))
    del model.graph.node[-1]
    model.graph.node.extend(nodes)
    model.graph.initializer.extend(initializers)
    destination.parent.mkdir(parents=True, exist_ok=True)
    print("Saving the runtime ONNX model.", flush=True)
    onnx.save(model, destination)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    compact_model(args.source, args.destination)

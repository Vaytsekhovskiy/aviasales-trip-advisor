import numpy as np
import pytest

onnx = pytest.importorskip("onnx")

from onnx import TensorProto, checker, helper
from onnx.reference import ReferenceEvaluator

from deploy.compact_price_model import encode_forest, forest_arrays


def test_compact_forest_preserves_original_branch_and_leaf_scores():
    original = helper.make_node(
        "TreeEnsembleRegressor", ["X"], ["Y"], domain="ai.onnx.ml",
        n_targets=1, post_transform="NONE",
        nodes_treeids=[0, 0, 0, 0, 0, 1, 1, 1], nodes_nodeids=[0, 1, 2, 3, 4, 0, 1, 2],
        nodes_modes=["BRANCH_LEQ", "LEAF", "BRANCH_LEQ", "LEAF", "LEAF", "BRANCH_LEQ", "LEAF", "LEAF"],
        nodes_featureids=[0] * 8, nodes_values=[2., 0., 4., 0., 0., 5., 0., 0.],
        nodes_truenodeids=[1, 0, 3, 0, 0, 1, 0, 0], nodes_falsenodeids=[2, 0, 4, 0, 0, 2, 0, 0],
        nodes_missing_value_tracks_true=[1, 0, 0, 0, 0, 0, 0, 0],
        target_ids=[0] * 5, target_nodeids=[4, 1, 3, 2, 1], target_treeids=[0, 0, 0, 1, 1],
        target_weights=[8., 9., 7., 1., 4.])
    compact, initializers = encode_forest(original, forest_arrays(original))
    data = np.array([[-1.], [2.], [3.], [4.], [5.], [6.], [np.nan]], dtype=np.float32)

    def scores(nodes, weights):
        graph = helper.make_graph(nodes, "parity",
            [helper.make_tensor_value_info("X", TensorProto.FLOAT, [None, 1])],
            [helper.make_tensor_value_info("Y", TensorProto.FLOAT, [None, 1])],
            initializer=weights)
        model = helper.make_model(graph, opset_imports=[helper.make_opsetid("", 18), helper.make_opsetid("ai.onnx.ml", 3)])
        checker.check_model(model)
        return ReferenceEvaluator(model).run(None, {"X": data})[0]

    np.testing.assert_array_equal(scores([original], []), scores(compact, initializers))

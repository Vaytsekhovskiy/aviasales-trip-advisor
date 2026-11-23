from __future__ import annotations

import numpy as np
import pandas as pd
from sklearn.metrics import mean_absolute_error, mean_squared_error, median_absolute_error


def regression_metrics(actual, predicted) -> dict:
    predicted = np.maximum(0, np.asarray(predicted, dtype=float))
    mse = float(mean_squared_error(actual, predicted))
    return {"mae": float(mean_absolute_error(actual, predicted)),
            "mse": mse,
            "rmse": float(mse ** .5),
            "medianAbsoluteError": float(median_absolute_error(actual, predicted))}


def group_mae(frame: pd.DataFrame, column: str, min_size: int = 1) -> list[dict]:
    rows = []
    for key, group in frame.groupby(column, dropna=False):
        if len(group) >= min_size:
            rows.append({column: str(key), "count": len(group),
                         "mae": float(group["absoluteError"].mean())})
    return sorted(rows, key=lambda x: x["mae"], reverse=True)

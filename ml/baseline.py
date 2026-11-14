from __future__ import annotations

import pandas as pd


class HierarchicalMedianBaseline:
    def fit(self, train: pd.DataFrame) -> "HierarchicalMedianBaseline":
        target = "delayTargetMin"
        self.exact = train.groupby(["serviceKey", "departureWeekday", "departureHour"])[target].median().to_dict()
        self.service = train.groupby("serviceKey")[target].median().to_dict()
        self.route = train.groupby("routeKey")[target].median().to_dict()
        self.airline = train.groupby("airlineIata")[target].median().to_dict()
        self.global_median = float(train[target].median())
        return self

    def predict_one(self, row: pd.Series | dict) -> float:
        exact = (row["serviceKey"], row["departureWeekday"], row["departureHour"])
        if exact in self.exact:
            return float(self.exact[exact])
        for mapping, key in ((self.service, row["serviceKey"]), (self.route, row["routeKey"]),
                             (self.airline, row["airlineIata"])):
            if key in mapping:
                return float(mapping[key])
        return self.global_median

    def predict(self, frame: pd.DataFrame):
        return frame.apply(self.predict_one, axis=1).to_numpy()

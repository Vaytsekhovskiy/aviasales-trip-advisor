package avia.common.flight.storage;

import avia.common.flight.model.FlightBatch;
import avia.common.flight.model.FlightRecord;

import java.io.IOException;
import java.util.List;

public interface FlightStorage {

    void save(List<FlightRecord> flights, FlightBatch batch) throws IOException;

    boolean exists(FlightBatch batch);
}

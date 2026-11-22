package avia.storage;

import avia.model.FlightBatch;
import avia.model.FlightRecord;

import java.io.IOException;
import java.util.List;

public interface FlightStorage {

    void save(List<FlightRecord> flights, FlightBatch batch) throws IOException;

    boolean exists(FlightBatch batch);
}

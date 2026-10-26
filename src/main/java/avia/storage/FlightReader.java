package avia.storage;

import avia.model.FlightBatch;
import avia.model.FlightRecord;
import java.io.IOException;
import java.util.List;

/** Reading is separate from writing so analysis works with CSV or future object storage. */
public interface FlightReader {
    List<FlightRecord> read(FlightBatch batch) throws IOException;
}

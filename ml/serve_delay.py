"""Private HTTP transport for the existing Task 5 CatBoost predictor."""

import json
import logging
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from threading import Lock

from .predictor import DelayPredictor, FlightPredictionRequest, InvalidRequestError, MongoHistoryRepository

log = logging.getLogger(__name__)


class DelayServer(ThreadingHTTPServer):
    def __init__(self, address):
        super().__init__(address, Handler)
        self.repository = None
        self.predictor = None
        self.model_lock = Lock()

    def predict(self, request):
        with self.model_lock:
            if self.predictor is None:
                repository = MongoHistoryRepository()
                try:
                    self.predictor = DelayPredictor(repository)
                except Exception:
                    repository.close()
                    raise
                self.repository = repository
            return self.predictor.predict(request)

    def server_close(self):
        super().server_close()
        with self.model_lock:
            if self.repository is not None:
                self.repository.close()


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/health":
            self.send_json(200, {"status": "UP"})
        else:
            self.send_json(404, {"status": "NOT_FOUND"})

    def do_POST(self):
        if self.path != "/predict":
            self.send_json(404, {"status": "NOT_FOUND"})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if not 0 < length <= 4096:
                raise InvalidRequestError("Invalid request size")
            self.connection.settimeout(10)
            body = json.loads(self.rfile.read(length))
            if not isinstance(body, dict):
                raise InvalidRequestError("Expected a JSON object")
            request = FlightPredictionRequest.from_dict(body)
        except (ValueError, UnicodeError, TimeoutError):
            self.send_json(400, {"status": "INVALID_REQUEST"})
            return

        try:
            result = self.server.predict(request)
            self.send_json(200 if result["status"] == "OK" else 409, result)
        except (BrokenPipeError, ConnectionResetError):
            return
        except Exception as error:
            log.warning("Delay prediction failed (%s)", type(error).__name__)
            self.send_json(503, {"status": "ERROR"})

    def send_json(self, status, payload):
        body = json.dumps(payload, ensure_ascii=False, allow_nan=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *_args):
        pass


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    host = os.environ.get("DELAY_HOST", "127.0.0.1")
    port = int(os.environ.get("DELAY_PORT", "8090"))
    with DelayServer((host, port)) as server:
        log.info("Delay service listening on %s:%d", host, port)
        server.serve_forever()

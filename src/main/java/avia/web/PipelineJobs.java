package avia.web;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class PipelineJobs {
    private static final Logger LOG = LoggerFactory.getLogger(PipelineJobs.class);
    private static final int RETAINED_JOBS = 100;

    public enum Stage { QUEUED, COLLECTING, PREPROCESSING, ANALYZING, COMPLETED, FAILED }
    public record Job(UUID jobId, int taskId, Stage status, String message, Instant updatedAt) { }

    @FunctionalInterface
    public interface Work {
        String run(Progress progress) throws Exception;
    }

    @FunctionalInterface
    public interface Progress {
        void update(Stage stage, String message);
    }

    private final Map<UUID, Job> jobs = new LinkedHashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Clock clock;
    private UUID active;

    public PipelineJobs(Clock clock) {
        this.clock = clock;
    }

    public synchronized Job start(int task, Work work) {
        if (active != null) throw new ResponseStatusException(CONFLICT, "Дождитесь завершения текущего обновления.");
        while (jobs.size() >= RETAINED_JOBS) jobs.remove(jobs.keySet().iterator().next());
        UUID id = UUID.randomUUID();
        var queued = update(id, task, Stage.QUEUED, "Обновление поставлено в очередь.");
        active = id;
        worker.execute(() -> run(queued, work));
        return queued;
    }

    public synchronized Job get(UUID id) {
        Job job = jobs.get(id);
        if (job == null) throw new ResponseStatusException(NOT_FOUND, "Задание не найдено.");
        return job;
    }

    private void run(Job job, Work work) {
        Stage status = Stage.FAILED;
        String message = "Обновление прервано.";
        try {
            message = work.run((stage, text) -> update(job.jobId(), job.taskId(), stage, text));
            status = Stage.COMPLETED;
        } catch (ResponseStatusException error) {
            message = error.getReason();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (Exception error) {
            LOG.warn("Task {} pipeline failed at {} ({})", job.taskId(), get(job.jobId()).status(),
                    error.getClass().getSimpleName());
            message = "Не удалось выполнить обновление. Проверьте доступ к источнику данных и MongoDB.";
        } finally {
            synchronized (this) {
                update(job.jobId(), job.taskId(), status, message);
                active = null;
            }
        }
    }

    private synchronized Job update(UUID id, int task, Stage stage, String message) {
        var job = new Job(id, task, stage, message, clock.instant());
        jobs.put(id, job);
        return job;
    }

    @PreDestroy
    void stop() {
        worker.shutdownNow();
    }
}

package avia.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api")
class PipelineController {
    private final PipelineJobs jobs;
    private final TaskPipelines pipelines;

    PipelineController(PipelineJobs jobs, TaskPipelines pipelines) {
        this.jobs = jobs;
        this.pipelines = pipelines;
    }

    @PostMapping("/tasks/{task}/pipeline")
    ResponseEntity<PipelineJobs.Job> start(@PathVariable int task, @RequestBody TaskPipelines.Parameters parameters) {
        var job = jobs.start(task, pipelines.plan(task, parameters));
        return ResponseEntity.accepted().location(URI.create("/api/jobs/" + job.jobId())).body(job);
    }

    @GetMapping("/jobs/{jobId}")
    PipelineJobs.Job job(@PathVariable UUID jobId) {
        return jobs.get(jobId);
    }
}

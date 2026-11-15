package avia.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class FoundationController {
    private final TaskRegistry taskRegistry;

    public FoundationController(TaskRegistry taskRegistry) {
        this.taskRegistry = taskRegistry;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }

    @GetMapping("/tasks")
    public List<TaskMetadata> tasks() {
        return taskRegistry.tasks();
    }
}

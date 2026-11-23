package avia.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskRegistryTest {
    @Test
    void containsExactlyFiveOrderedTasks() {
        var tasks = new TaskRegistry().tasks();
        assertEquals(5, tasks.size());
        assertEquals(java.util.List.of(1, 2, 3, 4, 5), tasks.stream().map(TaskMetadata::id).toList());
    }
}

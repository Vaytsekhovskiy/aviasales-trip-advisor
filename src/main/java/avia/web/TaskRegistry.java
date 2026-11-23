package avia.web;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TaskRegistry {
    private static final List<TaskMetadata> TASKS = List.of(
            new TaskMetadata(1, "Сезонность цен и дешёвые направления",
                    "Анализ календарной сезонности цен и поиск выгодных направлений.", true),
            new TaskMetadata(2, "Прямые рейсы и пересадки",
                    "Сравнение стоимости прямых и пересадочных перелётов.", true),
            new TaskMetadata(3, "Исторический анализ задержек",
                    "Описательная статистика задержек без машинного обучения.", true),
            new TaskMetadata(4, "Сравнение стоимости авиакомпаний",
                    "Сравнение ценовой политики авиакомпаний на сопоставимых маршрутах.", true),
            new TaskMetadata(5, "ML-прогнозирование цены и задержки",
                    "ONNX-прогноз цены и CatBoost-прогноз задержки.", true)
    );

    public List<TaskMetadata> tasks() {
        return TASKS;
    }
}

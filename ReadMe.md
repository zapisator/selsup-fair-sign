Код решенает [задание](Task.md).

1. **Ограничение на количество запросов:**

    * **Реализовано:** Да.
    * **Как реализовано:**
        * В классе `DefaultOutboundConfiguration` есть поля `timeUnit` и `requestLimit`, которые задают ограничение.
        * Класс `DefaultOutboundExecutor` использует `ScheduledExecutorService` для запуска задач с заданной периодичностью, рассчитанной на основе `timeUnit` и `requestLimit`.
        * Метод `toPeriod()` в `DefaultOutboundExecutor` вычисляет период выполнения задач, гарантируя, что лимит запросов не будет превышен.
    * **Замечания:**
        * Исключение `OutboundRequestExecutorException` выбрасывается, если период выполнения задач меньше 1 миллисекунды, что предотвращает проблемы с производительностью.

2. **Метод `createDocument`:**

    * **Реализовано:** Да.
    * **Как реализовано:**
        * Метод принимает объект `Document` и строку `signature` в качестве параметров.
        * Он логирует информацию о запросе и добавляет документ и подпись в очередь `inboundQueue` с помощью метода `collector.offer()`.
    * **Замечания:**
        * Отправка запроса происходит асинхронно в отдельном потоке, что не блокирует вызывающий поток.

3. **Thread-safety:**

    * **Реализовано:** Да.
    * **Как реализовано:**
        * Использована потокобезопасная очередь `MpscUnboundedArrayQueue` для хранения запросов.
        * `ScheduledExecutorService` гарантирует, что задачи выполняются в одном потоке, что обеспечивает потокобезопасность доступа к ресурсам.
        * Использование immutable objects (@Value) в классах DefaultOutboundConfiguration, Document, и Product существенно повышает потокобезопасность кода. 
        * Отсутствие побочных эффектов: Immutable objects не могут быть изменены после создания, что исключает возможность случайной модификации данных из разных потоков. 
      Сокращение рисков конкурентного доступа: Поскольку immutable objects нельзя изменить, нет необходимости в синхронизации доступа к их данным из разных потоков.
    * **Замечания:**
        * Код выглядит потокобезопасным.

4. **Реализация:**

    * **Библиотеки:**
        * Использованы Jackson для JSON-сериализации, OkHttp для HTTP-запросов, Lombok для сокращения шаблонного кода, SLF4j для логирования.
        * Выбор библиотек соответствует современным практикам.
    * **Расширяемость:**
        * Код хорошо структурирован с использованием интерфейсов (`InboundCollector`, `Serializer`, `HttpClient`, `OutboundExecutor`, `OutboundConfiguration`), что делает его легко расширяемым.
        * Можно легко заменить реализации компонентов, не затрагивая остальной код.
    * **Один файл:**
        * Весь код находится в одном файле `CrptApi.java`.
        * Это может быть допустимо для небольших проектов, но для больших проектов рекомендуется разделять код на несколько файлов для лучшей организации и поддержки.
    * **Внутренние классы:**
        * Все дополнительные классы являются внутренними классами `CrptApi`.
        * Это допустимый подход, который улучшает инкапсуляцию.

5. **Проверка:**

    * **Вызов метода:**
        * В методе `main` есть пример использования класса `CrptApi` с вызовом `createDocument`.
        * Реальный API не вызывается, вместо этого используется `reqres.in` как заглушка.
    * **Замечания:**
        * Пример использования демонстрирует работу класса.
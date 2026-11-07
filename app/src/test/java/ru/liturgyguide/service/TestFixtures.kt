package ru.liturgyguide.service

internal fun testGuideJson(): String = """
    {
      "service": "Тестовая литургия",
      "total_duration_min": 30,
      "source_url": "https://example.org/source",
      "content_note": "Учебный справочник",
      "stages": [
        {"id":"first", "order":1, "name":"Первый этап", "description":"Описание первого",
         "duration_min":10, "cue":"Слышен первый возглас", "texts":[]},
        {"id":"second", "order":2, "name":"Второй этап", "description":"Описание второго",
         "duration_min":10, "texts":[
           {"type":"возглас", "original":"Исходный текст", "translation":"Смысловой пересказ", "explanation":"Пояснение"}
         ]},
        {"id":"third", "order":3, "name":"Третий этап", "description":"Описание третьего",
         "duration_min":10, "texts":[]}
      ]
    }
""".trimIndent()

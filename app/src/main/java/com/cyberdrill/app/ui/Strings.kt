package com.cyberdrill.app.ui

/**
 * All UI text in both app languages. Spanish is the default (owner's rule
 * for every app he ships); English is one tap away in the top bar and the
 * choice persists in DataStore.
 */
class L(val lang: String) {
    private fun p(es: String, en: String): String = if (lang == "en") en else es

    val tagline get() = p(
        "Practica sin conexión. Aprueba con la nube.",
        "Drill offline. Pass with the cloud."
    )
    val topicsTitle get() = p("Temas de estudio", "Study topics")
    val fullExam get() = p("Examen completo", "Full exam")
    val fullExamDesc get() = p(
        "10 preguntas con explicación, generadas en la nube",
        "10 explained questions, generated in the cloud"
    )
    val progress get() = p("Progreso", "Progress")
    val flashcards get() = p("Tarjetas", "Flashcards")
    val flashcardsDesc get() = p("Términos y definiciones cortas", "Terms and short definitions")
    val miniQuiz get() = p("Mini-quiz", "Mini-quiz")
    val miniQuizDesc get() = p("5 preguntas rápidas por tema", "5 quick questions per topic")
    val aiStatusTitle get() = p("IA híbrida", "Hybrid AI")
    val seedMode get() = p(
        "Modo semilla: banco local activo. Conecta Firebase para activar la IA híbrida.",
        "Seed mode: local bank active. Connect Firebase to enable hybrid AI."
    )
    val onDeviceAvailable get() = p("Gemini Nano disponible en el dispositivo", "Gemini Nano available on device")
    val onDeviceDownloading get() = p("Descargando el modelo del dispositivo…", "Downloading the on-device model…")
    val onDeviceUnavailable get() = p(
        "Sin modelo en el dispositivo: la IA responde desde la nube",
        "No on-device model: AI answers from the cloud"
    )
    val onDeviceUnknown get() = p("Comprobando el modelo del dispositivo…", "Checking the on-device model…")
    val aiError get() = p("Error de IA", "AI error")
    val badgeOnDevice get() = p("En el dispositivo", "On device")
    val badgeInCloud get() = p("En la nube", "In the cloud")
    val badgeSeed get() = p("Banco local", "Local bank")
    val mastery get() = p("dominio", "mastery")
    val tapToReveal get() = p("Toca para ver la definición", "Tap to reveal the definition")
    val aiDefinition get() = p("Definición con IA", "AI definition")
    val nextCard get() = p("Siguiente tarjeta", "Next card")
    val cardOf get() = p("Tarjeta", "Card")
    val newAiQuestion get() = p("Pregunta nueva con IA", "New question with AI")
    val generating get() = p("Generando…", "Generating…")
    val checkAnswer get() = p("Comprobar", "Check answer")
    val nextQuestion get() = p("Siguiente pregunta", "Next question")
    val seeResults get() = p("Ver resultados", "See results")
    val correct get() = p("¡Correcto!", "Correct!")
    val incorrect get() = p("No es esa.", "Not that one.")
    val questionOf get() = p("Pregunta", "Question")
    val yourScore get() = p("Tu resultado", "Your score")
    val examIntro get() = p(
        "El examen se genera en la nube con explicaciones completas en tu idioma. Si la nube falla, verás el error exacto y podrás reintentar o elegir el banco local.",
        "The exam is generated in the cloud with full explanations in your language. If the cloud fails, you will see the exact error and can retry or choose the local bank."
    )
    val startExam get() = p("Comenzar el examen", "Start the exam")
    val examCloudErrorTitle get() = p(
        "El examen en la nube no se pudo generar",
        "The cloud exam could not be generated"
    )
    val examCloudErrorBody get() = p(
        "No se sustituyó por el banco local en silencio. Esto es lo que reportó la ruta de la nube:",
        "Nothing was silently swapped for the local bank. This is what the cloud route reported:"
    )
    val examRetry get() = p("Reintentar", "Try again")
    val examUseLocalBank get() = p("Usar el banco local", "Use the local bank")
    val preparingExam get() = p("Preparando tu examen…", "Preparing your exam…")
    val examWritingQuestions get() = p(
        "La IA está escribiendo tus preguntas…",
        "The AI is writing your questions…"
    )
    val examWritingExplanations get() = p(
        "Añadiendo la explicación de cada respuesta…",
        "Adding the explanation for each answer…"
    )
    val examAlmostThere get() = p(
        "Ya casi está, juntando tu examen…",
        "Almost there, putting your exam together…"
    )
    fun examQuestionsReady(ready: Int, total: Int): String =
        p("$ready de $total preguntas listas", "$ready of $total questions ready")
    val analysisTitle get() = p("Análisis de tu progreso", "Your progress analysis")
    val reviewTitle get() = p("Revisión de respuestas", "Answer review")
    val backHome get() = p("Volver al inicio", "Back to home")
    val sessions get() = p("Sesiones", "Sessions")
    val answered get() = p("Preguntas respondidas", "Questions answered")
    val accuracy get() = p("Aciertos", "Accuracy")
    val history get() = p("Historial reciente", "Recent history")
    val noHistory get() = p(
        "Todavía no hay sesiones. Tu progreso aparecerá aquí.",
        "No sessions yet. Your progress will appear here."
    )
    val modeFlashcards get() = p("Tarjetas", "Flashcards")
    val modeMiniQuiz get() = p("Mini-quiz", "Mini-quiz")
    val modeExam get() = p("Examen", "Exam")
    val cardsSeen get() = p("tarjetas vistas", "cards seen")
    val studyThisTopic get() = p("Estudiar este tema", "Study this topic")
    val chooseMode get() = p("¿Cómo quieres practicar?", "How do you want to practice?")
}

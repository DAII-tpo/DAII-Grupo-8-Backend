# ADR-002 — Regresión logística entrenada para rankear estaciones

- **Estado:** Aceptado
- **Tickets:** MOV-041 (SCRUM-68)

## Contexto

La recomendación tiene que considerar proximidad **y** disponibilidad (criterio de aceptación de MOV-041), y
la dimensión "IA/ML/I+D" de la rúbrica pide modelos de IA/ML bien aplicados. El problema es de **ranking**:
dado un conjunto de estaciones candidatas, ordenar cuál conviene más para retirar (`PICKUP`) o devolver
(`DROPOFF`) una bicicleta. Además, el frontend necesita **explicar** por qué se recomienda una estación más
lejana que otra.

Restricciones: no hay datos reales de uso (proyecto académico), el volumen de datos es chico y el servicio
tiene que responder en milisegundos.

## Alternativas

1. **Fórmula de pesos fijos** (`score = w1·disponibilidad − w2·distancia`). No es ML: los pesos los decide
   una persona y no se adaptan a datos. Mismo motivo por el que el Grupo 1 descartó "reglas estáticas" en su
   ADR-010.
2. **Isolation Forest.** Resuelve detección de anomalías, no ranking.
3. **Deep learning.** Sin justificación para cinco features y datos sintéticos; costo y opacidad
   innecesarios.
4. **k-Nearest Neighbors.** Aprende de ejemplos, pero no da probabilidades bien calibradas ni coeficientes:
   la única explicación posible es "se parece a otros casos", que no sirve para el mensaje al usuario.
5. **Regresión logística (scikit-learn).**

## Decisión

Se adopta **regresión logística** (`Pipeline(StandardScaler, LogisticRegression)`), un modelo por propósito.

- Clasifica cada candidata como "buena elección" (1) o no (0); `predict_proba` es el score de ranking.
- Features por candidata, relativas al recurso que importa (bicis en PICKUP, anclajes en DROPOFF):
  distancia, distancia extra respecto de la más cercana, unidades disponibles saturadas en un nivel seguro
  (5), proporción sobre la capacidad y proporción respecto de la mejor candidata.
- Los pesos **se aprenden** de un dataset de entrenamiento. Hoy ese dataset es sintético (la regla de
  sentido común vive solo en el etiquetado). El mismo pipeline puede reentrenar con otra fuente (por ejemplo,
  un historial de recomendaciones con la estación que el usuario eligió) implementando `TrainingDataSource`.
- La contribución de cada feature (coeficiente × valor estandarizado) se usa para construir la
  **explicación** que ve el usuario.

## Consecuencias

- **Positivas:** modelo entrenado y evaluable (ROC-AUC, accuracy, acierto top-1 en un holdout); coeficientes
  interpretables publicados en el model card; explicación derivada del modelo; artefacto liviano y
  entrenamiento en segundos, reproducible con semilla fija.
- **Negativas:** modelo lineal: relaciones muy no lineales requieren nuevas features; con datos sintéticos
  el modelo aprende compromisos generales (distancia vs. escasez), no patrones reales de estaciones u horarios.
- **Evolución:** el `Scorer` y la fuente de datos de entrenamiento son intercambiables; se puede incorporar un
  modelo distinto o features horarias sin cambiar el contrato con el backend.

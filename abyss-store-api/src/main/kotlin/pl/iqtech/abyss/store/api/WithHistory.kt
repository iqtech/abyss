package pl.iqtech.abyss.store.api

// Keeps every committed state of this node type in node_history. Persistent store only.
// Don't remove it and add it back on the same class: the old rows stay, and a re-created id collides with them.
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class WithHistory

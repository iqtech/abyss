package pl.iqtech.abyss.store.api

// Keeps every committed state of this node type in node_history. Persistent store only.
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class WithHistory

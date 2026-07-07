package pl.iqtech.abyss.store.api

import kotlin.reflect.KClass

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class EdgeConstraint(
    val fromTypes: Array<KClass<*>> = [],
    val toTypes:   Array<KClass<*>> = []
)

package pl.iqtech.abyss.dsl

import java.util.UUID

data class EdgeKey(val fromId: UUID, val toId: UUID, val type: String)

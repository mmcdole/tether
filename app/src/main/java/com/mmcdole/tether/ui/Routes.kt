package com.mmcdole.tether.ui

import kotlinx.serialization.Serializable

@Serializable
data object WorldsRoute

/** A null [id] adds a new world. */
@Serializable
data class WorldEditRoute(val id: Long? = null)

/** The live session. There is only ever one, so it needs no arguments. */
@Serializable
data object SessionRoute

@Serializable
data object SettingsRoute

package com.curveon.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A group of GPX tracks, like a layer group: by default the tracks of a multi-track file.
 * Each group setting either overrides its tracks or is [Override.CUSTOM], in which case every
 * track uses its own value. The tracks' own values are kept while an override is on, so
 * switching back to custom brings them back.
 */
@Entity(tableName = "gpx_groups")
data class GpxGroup(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    /** [Override.CUSTOM], [Override.ON] (all shown) or [Override.OFF] (all hidden). */
    val visibility: Int = Override.CUSTOM,
    /** [Override.CUSTOM], [Override.ON] (all inverted) or [Override.OFF] (all as recorded). */
    val direction: Int = Override.CUSTOM,
    /** All tracks drawn in [color] instead of their own colours. */
    val sameColor: Boolean = false,
    /** ARGB; 0 = not chosen yet. */
    val color: Int = 0
) {
    object Override {
        const val CUSTOM = 0
        const val ON = 1
        const val OFF = 2
    }
}

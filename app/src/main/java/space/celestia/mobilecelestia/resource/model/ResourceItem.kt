/*
 * ResourceItem.kt
 *
 * Copyright (C) 2001-2020, Celestia Development Team
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package space.celestia.mobilecelestia.resource.model

import java.io.Serializable
import java.util.*

class ResourceItem(
    override val id: String,
    override val name: String,
    val description: String,
    val item: String,
    val image: String?,
    val authors: List<String>?,
    val publishTime: Date?,
    val objectName: String?
): AsyncListItem, Serializable
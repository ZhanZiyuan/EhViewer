/*
 * Copyright 2016 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ehviewer.core.model

import kotlinx.serialization.Serializable

@Serializable
data class GalleryComment(
    val id: Long,
    val score: Int,
    val editable: Boolean,
    val voteUpAble: Boolean,
    val voteUpEd: Boolean,
    val voteDownAble: Boolean,
    val voteDownEd: Boolean,
    val uploader: Boolean,
    val voteState: String?,
    val time: Long,
    val user: String?,
    val comment: String,
    val lastEdited: Long,
    /** Parsed by the existing Rust HTML transport; old persisted/wire comments remain readable. */
    val body: List<GalleryCommentText> = emptyList(),
)

@Serializable
data class GalleryCommentText(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val link: String? = null,
    val image: String? = null,
)

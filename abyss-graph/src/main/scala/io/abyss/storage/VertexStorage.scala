/*
 * This software is licensed under the Apache 2 license, quoted below.
 *
 * Copyright 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package io.abyss.storage

import io.abyss.graph.internal.VertexState


/**
 * Created by cane, 22.06.13 17:43
 * $Id: VertexStorage.scala,v 1.2 2013-12-31 21:09:28 cane Exp $
 */
object VertexStorage {
	 def apply (id: String): Option[ VertexState ] = {
		 // todo use database here
		 None
	 }


	 def apply (state: VertexState): Boolean = {
		 true
	 }
 }
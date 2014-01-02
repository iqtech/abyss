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

package io.abyss.graph.internal

import io.abyss.node._

/**
 * Vertex internal state representation.
 * @param id Unique identifier of element. Usually UUID.
 * @param dc Data center number, bound to cluster (exists in boundaries of single data center).
 * @param shard Shard number, calculated from identifier's hash.
 * @param graph Graph to which this element belongs.
 * @param collection Collection to which this element belongs.
 * @param data Element's data.
 */
case class VertexState (id: String,
						dc: Short,
						shard: Short,
						graph: String,
						collection: String,
						data: Option[AnyRef])
	extends GraphElementState


object VertexState {
	def apply (id: String, graph: String, data: Option[AnyRef]): VertexState = {
		VertexState (id = id, dc = dataCenterId, shard = shardId (id),
			graph = graph, collection = data.getClass.getSimpleName, data = data)
	}

}




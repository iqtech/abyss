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
 * Graph edge state internal representation, immutable.
 * @param id Unique identifier of element. Usually UUID.
 * @param dc Data center number, bound to cluster (exists in boundaries of single data center).
 * @param shard Shard number, calculated from identifier's hash.
 * @param graph Graph to which this element belongs.
 * @param collection Collection to which this element belongs.
 * @param fromVertex Identifier of vertex where this edge starts.
 * @param toVertex Identifier of vertex where this edge ends.
 * @param bidirectional When true, then this is bidirectional edge.
 * @param data Element's data.
 */
case class EdgeState (id: String,
					  dc: Short,
					  shard: Short,
					  graph: String,
					  collection: String,
					  fromVertex: String,
					  toVertex: String,
					  bidirectional: Boolean,
					  data: Option[AnyRef])
	extends GraphElementState


object EdgeState {
	def apply (id: String, graph: String, fromVertex: String, toVertex: String,
			   bidirectional: Boolean, data: Option[AnyRef]): EdgeState = {
		EdgeState (
			id = id,
			dc = dataCenterId,
			shard = shardId (id),
			graph = graph,
			collection = data.getClass.getSimpleName,
			fromVertex = fromVertex,
			toVertex = toVertex,
			bidirectional = bidirectional,
			data = data)
	}
}



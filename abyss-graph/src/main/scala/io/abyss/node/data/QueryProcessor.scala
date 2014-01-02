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

package io.abyss.node.data

import java.util.concurrent.ConcurrentMap
import io.abyss._
import io.abyss.ReadMany
import io.abyss.InVertices
import io.abyss.OutVertices
import io.abyss.graph.model.GraphElement


/**
 * Created by cane, 15.07.13 16:44
 * $Id: QueryProcessor.scala,v 1.3 2014-01-02 09:35:15 cane Exp $
 */
trait QueryProcessor extends AbyssActor {
	val memory: ConcurrentMap[ String, GraphElement ]


	// TODO

	def receiveQuery: Receive = {

		case msg: ReadMany =>
			sender ! msg.ids.map (memory.get(_).state).toArray

		case msg: InVertices =>

		case msg: OutVertices =>

		case msg: InOutVertices =>

		case msg: InEdges =>
//			val v = memory.get(msg.id).asInstanceOf[Vertex] //.state.asInstanceOf[VertexState]
//			v.inEdges foreach {
//				eid =>
//					val sid = shardId(eid)
//					// TODO ask other node if shard not owned by me
//			}

		case msg: OutEdges =>

		case msg: InOutEdges =>

		case msg: Read =>
			sender ! memory.get(msg.id).state

	}
}
